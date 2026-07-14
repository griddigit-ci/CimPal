Manifest Service

This CLI wrapper invokes `ManifestGenerator` (from `CimPal-Core`) to create a `manifest.ttl` file from a folder of model RDF/XML files. It is built as a self-contained (shaded) executable jar: `CimPal-CLI.jar`.

Usage

- Run against a models folder (default output is the parent folder of the models folder):
  java -jar CimPal-CLI.jar --dir "Instance\Belgovia\Grid\cimxml"

- Or list files explicitly:
  java -jar CimPal-CLI.jar --files "path\to\file1.xml,path\to\file2.xml"

Options
- --accessUrl <url>  : optional base access URL to include in distributions
- --output <file>    : explicit output file path for manifest (overrides default)

Building

- From the repo root: `mvn -pl CimPal-CLI -am package`
- Produces `CimPal-CLI/target/CimPal-CLI.jar`, a fat jar with all dependencies (Jena, etc.) bundled and `Main-Class` set, so it can be run directly with `java -jar`.

Getting the jar in another repo

Releases of this repo attach `CimPal-CLI.jar` (alongside `CimPal.jar` / `CimPal.exe` for the main app) to each GitHub Release, tagged with a date-based version (e.g. `2026.07.14.1`). A consuming repo's workflow can download it with the GitHub CLI:

    - name: Download ManifestService CLI
      run: gh release download --repo griddigit/CimPal --pattern 'CimPal-CLI*.jar' --dir tools --clobber
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

Add `--repo griddigit/CimPal <tag>` (positional) instead of omitting it if you want to pin to a specific release rather than the latest one.

Running on multiple models in GitHub Actions

Use a matrix strategy so each model folder gets its own job, all sharing the same downloaded jar:

    jobs:
      generate-manifests:
        runs-on: ubuntu-latest
        strategy:
          matrix:
            model_dir:
              - Instance/Belgovia/Grid/cimxml
              - Instance/Elbonia/Grid/cimxml
        steps:
          - uses: actions/checkout@v4

          - uses: actions/setup-java@v4
            with:
              distribution: temurin
              java-version: '25'

          - name: Download ManifestService CLI
            run: gh release download --repo griddigit/CimPal --pattern 'CimPal-CLI*.jar' --dir tools --clobber
            env:
              GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

          - name: Generate manifest for ${{ matrix.model_dir }}
            run: java -jar tools/CimPal-CLI.jar --dir "${{ matrix.model_dir }}"

Notes
- The service reads RDF files via Apache Jena and writes a Turtle manifest using the existing `ManifestGenerator` API.
- By default it writes `manifest.ttl` into the parent folder of the models folder (matching repository layout).
- No token is strictly required to download assets from a public release; `GITHUB_TOKEN` just raises the API rate limit and is provided for free by Actions.