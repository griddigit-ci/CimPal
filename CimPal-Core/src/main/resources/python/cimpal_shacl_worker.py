"""CimPal's in-memory bridge for optional Python SHACL engines.

Input and output are Turtle on standard streams.  The model streams are never written to
local files: Java has already selected and datatype-enriched the mapping's data graph, and
already resolved owl:imports for the matching shapes graph.
"""
import io
import sys
import traceback

END_SHAPES = b"#CIMPAL-END-SHAPES\n"
END_DATA = b"#CIMPAL-END-DATA\n"


def read_section(marker):
    chunks = []
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            raise RuntimeError("CimPal Python validator received an incomplete RDF stream")
        if line == marker:
            return b"".join(chunks)
        chunks.append(line)


def pyshacl_validate(engine, shapes_bytes, data_bytes):
    from rdflib import Graph
    from pyshacl import validate

    shapes = Graph().parse(data=shapes_bytes, format="turtle")
    if engine == "PYSHACL_OXIGRAPH":
        from pyoxigraph import Store, RdfFormat
        data = Store()
        # Store.load keeps the graph in memory. bulk_load is deliberately not used because it
        # creates on-disk files, which would defeat CimPal's streaming input design.
        data.load(data_bytes, format=RdfFormat.TURTLE)
    else:
        data = Graph().parse(data=data_bytes, format="turtle")

    conforms, report, _ = validate(
        data, shacl_graph=shapes, inference="none", advanced=True,
        do_owl_imports=False, serialize_report_graph=False
    )
    return conforms, report.serialize(format="turtle")


def rust_validate(shapes_bytes, data_bytes):
    from shacl import Shapes
    shapes = Shapes.from_turtle(shapes_bytes.decode("utf-8"))
    report = shapes.validate_turtle(data_bytes.decode("utf-8"))
    return report.conforms, report.serialize()


def main():
    engine = sys.stdin.buffer.readline().decode("utf-8").strip()
    shapes = read_section(END_SHAPES)
    data = read_section(END_DATA)
    if engine in ("PYSHACL", "PYSHACL_OXIGRAPH"):
        conforms, report = pyshacl_validate(engine, shapes, data)
    elif engine == "RUST_SHACL":
        conforms, report = rust_validate(shapes, data)
    else:
        raise RuntimeError("Unsupported Python SHACL engine: " + engine)

    sys.stdout.write("#CIMPAL-CONFORMS=" + str(bool(conforms)).lower() + "\n")
    if isinstance(report, bytes):
        sys.stdout.buffer.write(report)
    else:
        sys.stdout.write(report)
    sys.stdout.flush()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc(file=sys.stderr)
        sys.exit(2)
