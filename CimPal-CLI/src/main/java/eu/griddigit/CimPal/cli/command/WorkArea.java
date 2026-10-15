/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.ZipExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code serve}'s file exchange area (DEP-7), {@code --work-root}:
 * <pre>
 *   .cimpal-work                      marker: this folder belongs to serve
 *   uploads/.incoming/&lt;uuid&gt;.zip   an upload while it arrives
 *   uploads/&lt;uploadId&gt;/            its extracted files (read-only for jobs)
 *   jobs/&lt;jobId&gt;/out/               a job's outputs
 * </pre>
 * Uploads and workspaces are known only in memory, like the jobs; at start {@code uploads/} and
 * {@code jobs/} are emptied, but only in a folder that carries the marker. A folder that is not
 * empty and has no marker is refused, so a mistyped {@code --work-root} never deletes anything.
 *
 * <p>The area is excluded from every job's path policy, so no request <em>path</em> reaches
 * another caller's files. Uploads and job outputs belong to a client (DEP-13): through the API a
 * client reaches only its own, an admin everyone's.
 *
 * <p>Disk use is bounded by {@code --work-max-bytes}: an upload reserves its declared size (or
 * the upload limit) before it is read, and the room it may extract into before extracting, so
 * concurrent uploads can't overshoot the budget together. Every extracted file and folder costs
 * {@code ZipExtractor}'s per-entry overhead on top of its bytes.
 */
final class WorkArea {

    static final String MARKER = ".cimpal-work";

    /** Thrown with the HTTP status to answer. */
    static final class Refused extends Exception {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /**
     * One upload: its files, size, lifetime and owner (the client that sent it, DEP-13);
     * {@code users} counts queued and running jobs.
     */
    static final class Upload {
        final UUID id;
        final String owner;
        final Path dir;
        final List<ZipExtractor.Entry> files;
        final long totalBytes;
        final long chargedBytes;
        final Instant createdAt;
        final Instant expiresAt;
        private int users;

        Upload(UUID id, String owner, Path dir, ZipExtractor.Result result, Instant createdAt, Instant expiresAt) {
            this.id = id;
            this.owner = owner;
            this.dir = dir;
            this.files = result.files();
            this.totalBytes = result.files().stream().mapToLong(ZipExtractor.Entry::size).sum();
            this.chargedBytes = result.chargedBytes();
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        synchronized int users() {
            return users;
        }
    }

    /** The folders of one job that uses an upload. */
    record JobWorkspace(UUID jobId, Path uploadDir, Path outDir) {
    }

    /** A file in a job's {@code out/}: its {@code /}-separated path there and size. */
    record OutputFile(String path, long size) {
    }

    private final Path root;
    private final Path uploads;
    private final Path incoming;
    private final Path jobs;
    private final long maxUploadBytes;
    private final long maxBytes;
    private final Duration uploadTtl;
    private final InstantSource clock;

    private final Map<UUID, Upload> uploadsById = new HashMap<>();
    /** Job id → its upload id while the job is queued or running; null once it ended. */
    private final Map<UUID, UUID> jobUpload = new HashMap<>();
    /** Job id → bytes its outputs take, once it ended. */
    private final Map<UUID, Long> jobBytes = new HashMap<>();
    /** Job id → the client whose share its outputs count against (DEP-13). */
    private final Map<UUID, String> jobOwner = new HashMap<>();
    private long usedBytes;
    private long reservedBytes;
    /** Per client: the bytes its uploads and job outputs take, and those reserved for its uploads now. */
    private final Map<String, Long> ownerUsed = new HashMap<>();
    private final Map<String, Long> ownerReserved = new HashMap<>();

    /**
     * Opens (or creates) the work area and empties what an earlier run left.
     *
     * @throws IOException when the folder is not empty and isn't a work area of {@code serve}
     */
    WorkArea(Path root, long maxUploadBytes, long maxBytes, Duration uploadTtl, InstantSource clock) throws IOException {
        this.maxUploadBytes = maxUploadBytes;
        this.maxBytes = maxBytes;
        this.uploadTtl = Objects.requireNonNull(uploadTtl);
        this.clock = Objects.requireNonNull(clock);
        Files.createDirectories(root);
        this.root = root.toRealPath();
        Path marker = this.root.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.root)) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("the folder is not empty and is not a work area of serve (no " + MARKER
                            + " marker); choose an empty or new folder");
                }
            }
            Files.writeString(marker, "Work area of cimpal serve (uploads and job outputs). Emptied at every start.\n");
        }
        this.uploads = this.root.resolve("uploads");
        this.incoming = uploads.resolve(".incoming");
        this.jobs = this.root.resolve("jobs");
        // Left over from an earlier run: nothing in memory refers to them any more.
        ZipExtractor.deleteTree(uploads);
        ZipExtractor.deleteTree(jobs);
        Files.createDirectories(incoming);
        Files.createDirectories(jobs);
    }

    Path root() {
        return root;
    }

    // ---- uploads -------------------------------------------------------------------------

    /**
     * Receives an upload: streams {@code body} to disk (at most {@code --max-upload-bytes}),
     * extracts it, and registers it.
     *
     * @param declaredLength the request's Content-Length, or -1 when unknown (or chunked)
     * @throws Refused 413 (too large), 507 (work area full) or 400 (not a usable ZIP)
     */
    Upload receive(InputStream body, long declaredLength) throws Refused, IOException {
        return receive(body, declaredLength, TokenSource.Client.DEFAULT.name(), maxUploadBytes, maxBytes);
    }

    /**
     * {@link #receive(InputStream, long)} for client {@code owner} (DEP-13), whose uploads may be
     * at most {@code ownerMaxUpload} bytes and whose uploads and job outputs may take at most
     * {@code ownerMaxBytes} of the work area.
     *
     * @throws Refused 413 (too large), 507 (work area or the client's share full) or 400 (not a usable ZIP)
     */
    Upload receive(InputStream body, long declaredLength, String owner, long ownerMaxUpload, long ownerMaxBytes)
            throws Refused, IOException {
        long maxUpload = Math.min(maxUploadBytes, ownerMaxUpload);
        if (declaredLength > maxUpload) {
            throw tooLarge(maxUpload);
        }
        long reserve = declaredLength >= 0 ? declaredLength : maxUpload;
        reserve(reserve, owner, ownerMaxBytes);
        UUID id = UUID.randomUUID();
        Path zip = incoming.resolve(id + ".zip");
        Path dir = uploads.resolve(id.toString());
        long allowance = 0;
        try {
            copyBounded(body, zip, maxUpload);
            // The room the extracted files may take, reserved now so a concurrent upload can't
            // count on it too. The archive itself is deleted right after.
            ZipExtractor.Limits limits = ZipExtractor.Limits.defaults();
            synchronized (this) {
                long room = Math.max(0, maxBytes - usedBytes - reservedBytes + reserve);
                long ownerRoom = Math.max(0, ownerMaxBytes - ownerBytes(owner) + reserve);
                allowance = Math.min(limits.maxTotalBytes(), Math.min(room, ownerRoom));
                reservedBytes += allowance;
                ownerReserved.merge(owner, allowance, Long::sum);
            }
            if (allowance < limits.maxTotalBytes()) {
                limits = limits.withMaxTotalBytes(allowance);
            }
            ZipExtractor.Result result;
            try {
                result = ZipExtractor.extractCharged(zip, dir, limits);
            } catch (IOException e) {
                throw new Refused(400, "Not a usable ZIP archive: " + e.getMessage());
            }
            Instant now = clock.instant();
            Upload upload = new Upload(id, owner, dir, result, now, now.plus(uploadTtl));
            synchronized (this) {
                uploadsById.put(id, upload);
                usedBytes += upload.chargedBytes;
                charge(owner, upload.chargedBytes);
            }
            return upload;
        } finally {
            synchronized (this) {
                reservedBytes -= reserve + allowance;
                long left = ownerReserved.getOrDefault(owner, 0L) - (reserve + allowance);
                if (left == 0) {
                    ownerReserved.remove(owner);
                } else {
                    ownerReserved.put(owner, left);
                }
            }
            try {
                Files.deleteIfExists(zip);
            } catch (IOException e) {
                // e.g. a virus scanner holding it; cleaned up at the next start
            }
        }
    }

    private synchronized void reserve(long bytes, String owner, long ownerMaxBytes) throws Refused {
        evictLocked();
        if (usedBytes + reservedBytes + bytes > maxBytes) {
            throw new Refused(507, "The server's work area is full (--work-max-bytes " + maxBytes
                    + "); delete uploads or wait for old jobs to expire");
        }
        if (ownerBytes(owner) + bytes > ownerMaxBytes) {
            throw new Refused(507, "This client's share of the work area is full (maxWorkBytes " + ownerMaxBytes
                    + "); delete uploads or wait for old jobs to expire");
        }
        reservedBytes += bytes;
        ownerReserved.merge(owner, bytes, Long::sum);
    }

    /** Under this lock: what {@code owner} takes and has reserved. */
    private long ownerBytes(String owner) {
        return ownerUsed.getOrDefault(owner, 0L) + ownerReserved.getOrDefault(owner, 0L);
    }

    /** Under this lock: {@code bytes} more (or, negative, fewer) for {@code owner}. */
    private void charge(String owner, long bytes) {
        if (owner != null) {
            long now = ownerUsed.getOrDefault(owner, 0L) + bytes;
            if (now == 0) {
                ownerUsed.remove(owner);
            } else {
                ownerUsed.put(owner, now);
            }
        }
    }

    private Refused tooLarge(long maxUpload) {
        return new Refused(413, maxUpload < maxUploadBytes
                ? "Upload larger than this client's maxUploadBytes (" + maxUpload + " bytes)"
                : "Upload larger than --max-upload-bytes (" + maxUploadBytes + " bytes)");
    }

    /** Copies at most {@code maxUpload} bytes; more is refused with 413 and nothing is kept. */
    private void copyBounded(InputStream body, Path target, long maxUpload) throws Refused, IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            for (int n = body.read(buffer); n >= 0; n = body.read(buffer)) {
                total += n;
                if (total > maxUpload) {
                    throw tooLarge(maxUpload);
                }
                out.write(buffer, 0, n);
            }
        }
    }

    synchronized Upload upload(UUID id) {
        return uploadsById.get(id);
    }

    /**
     * Deletes an upload.
     *
     * @return false when there is none
     * @throws Refused 409 while a queued or running job uses it
     */
    boolean deleteUpload(UUID id) throws Refused {
        Upload upload;
        synchronized (this) {
            upload = uploadsById.get(id);
            if (upload == null) {
                return false;
            }
            if (upload.users() > 0) {
                throw new Refused(409, "A queued or running job uses this upload");
            }
            uploadsById.remove(id);
            usedBytes -= upload.chargedBytes;
            charge(upload.owner, -upload.chargedBytes);
        }
        ZipExtractor.deleteTree(upload.dir);
        return true;
    }

    // ---- job workspaces ------------------------------------------------------------------

    /**
     * Prepares the workspace of a job of client {@code owner} that reads {@code uploadId}: its
     * {@code out/} folder, and the upload marked as in use until {@link #jobEnded} (or
     * {@link #deleteWorkspace}). The job's outputs count against {@code owner}'s share.
     *
     * @return null when there is no such upload, or it isn't {@code owner}'s (DEP-13)
     */
    JobWorkspace prepare(UUID jobId, UUID uploadId, String owner) throws IOException {
        Upload upload;
        synchronized (this) {
            upload = uploadsById.get(uploadId);
            if (upload == null || !upload.owner.equals(owner)) {
                return null;
            }
            synchronized (upload) {
                upload.users++;
            }
            jobUpload.put(jobId, uploadId);
            jobOwner.put(jobId, owner);
        }
        Path out = jobs.resolve(jobId.toString()).resolve("out");
        try {
            Files.createDirectories(out);
            return new JobWorkspace(jobId, upload.dir, out.toRealPath());
        } catch (IOException | RuntimeException e) {
            deleteWorkspace(jobId); // the upload must not stay pinned
            throw e;
        }
    }

    /** The job ended (finished, failed or cancelled): its upload may expire again, its outputs count. */
    void jobEnded(UUID jobId) {
        long bytes = size(jobs.resolve(jobId.toString()));
        synchronized (this) {
            if (!jobUpload.containsKey(jobId)) {
                return; // its workspace was deleted meanwhile
            }
            release(jobUpload.put(jobId, null));
            Long before = jobBytes.put(jobId, bytes);
            usedBytes += bytes - (before == null ? 0 : before);
            charge(jobOwner.get(jobId), bytes - (before == null ? 0 : before));
        }
    }

    /** Removes a job's workspace (the job left the store, or its submission was refused). */
    void deleteWorkspace(UUID jobId) {
        synchronized (this) {
            if (!jobUpload.containsKey(jobId)) {
                return;
            }
            release(jobUpload.remove(jobId));
            Long bytes = jobBytes.remove(jobId);
            usedBytes -= bytes == null ? 0 : bytes;
            charge(jobOwner.remove(jobId), -(bytes == null ? 0 : bytes));
        }
        ZipExtractor.deleteTree(jobs.resolve(jobId.toString()));
    }

    /** Under this lock: one user fewer for the upload, if the job still held it. */
    private void release(UUID uploadId) {
        Upload upload = uploadId == null ? null : uploadsById.get(uploadId);
        if (upload != null) {
            synchronized (upload) {
                upload.users = Math.max(0, upload.users - 1);
            }
        }
    }

    /** True when the job has a workspace (it used an upload). */
    synchronized boolean hasWorkspace(UUID jobId) {
        return jobUpload.containsKey(jobId);
    }

    /** The files in a job's {@code out/}, sorted by path; links and other non-files are left out. */
    List<OutputFile> outputs(UUID jobId) throws IOException {
        Path out = jobs.resolve(jobId.toString()).resolve("out");
        if (!Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<OutputFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(new OutputFile(out.relativize(p).toString().replace('\\', '/'), Files.size(p)));
                }
            }
        }
        files.sort(Comparator.comparing(OutputFile::path));
        return files;
    }

    /**
     * The file at {@code segments} (decoded, {@code /}-separated parts) inside a job's
     * {@code out/}, or null. Refuses {@code .}, {@code ..}, empty parts, separators, colons,
     * control characters and names the file system can't express in any part, links, and anything
     * whose real path leaves {@code out/}.
     */
    Path output(UUID jobId, List<String> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        try {
            Path out = jobs.resolve(jobId.toString()).resolve("out");
            Path file = out;
            for (String s : segments) {
                if (s.isEmpty() || s.equals(".") || s.equals("..") || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0
                        || s.indexOf(':') >= 0 || LogSanitizer.hasUnsafeCharacters(s)) {
                    return null;
                }
                file = file.resolve(s);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path real = file.toRealPath();
            return real.startsWith(out.toRealPath()) ? real : null;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    // ---- retention -----------------------------------------------------------------------

    /** Deletes uploads past {@code --upload-ttl} that no queued or running job uses. */
    synchronized void evict() {
        evictLocked();
    }

    /** Under this lock, so the space counts as free only once the folders are gone. */
    private List<Upload> evictLocked() {
        Instant now = clock.instant();
        List<Upload> expired = new ArrayList<>();
        for (Upload u : uploadsById.values()) {
            if (!now.isBefore(u.expiresAt) && u.users() == 0) {
                expired.add(u);
            }
        }
        for (Upload u : expired) {
            uploadsById.remove(u.id);
            usedBytes -= u.chargedBytes;
            charge(u.owner, -u.chargedBytes);
        }
        expired.forEach(u -> ZipExtractor.deleteTree(u.dir));
        return expired;
    }

    synchronized long usedBytes() {
        return usedBytes;
    }

    /** What {@code owner}'s uploads and job outputs take (tests). */
    synchronized long usedBytes(String owner) {
        return ownerUsed.getOrDefault(owner, 0L);
    }

    private static long size(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** For tests: how many entries are in {@code uploads/.incoming}. */
    long incomingCount() throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(incoming)) {
            long n = 0;
            for (Path ignored : s) {
                n++;
            }
            return n;
        }
    }
}
