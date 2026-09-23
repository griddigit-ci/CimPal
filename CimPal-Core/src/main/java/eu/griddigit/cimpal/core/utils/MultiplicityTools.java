package eu.griddigit.cimpal.core.utils;

//Parsing of the CIM RDFS multiplicity, this is the part after the #M: of the cims:multiplicity resource, e.g. the
//1..n of ...19990926#M:1..n
public class MultiplicityTools {

    //the upper bound that is used when the multiplicity does not define one, e.g. the n in 1..n
    public static final int UNBOUNDED = 999;

    public static class Bounds {
        public final int lowerBound;
        public final int upperBound;

        public Bounds(int lowerBound, int upperBound) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }
    }

    //the multiplicity is normally given as x..y, but it can also be given as a single value, e.g. M:1 or M:2, which
    //means that exactly that number of occurrences is expected. Both bounds can have more than one digit, e.g. 10..20.
    public static Bounds parse(String multiplicity) {
        int boundsSeparator = multiplicity.indexOf("..");
        if (boundsSeparator < 0) {
            return new Bounds(parseBound(multiplicity, 0), parseBound(multiplicity, UNBOUNDED));
        }
        return new Bounds(
                parseBound(multiplicity.substring(0, boundsSeparator), 0),
                parseBound(multiplicity.substring(boundsSeparator + 2), UNBOUNDED));
    }

    //parses one bound of the multiplicity. The given default is used when the bound is not a number, which is the case
    //for the upper bound "to many", e.g. the n in 1..n
    private static int parseBound(String bound, int defaultBound) {
        try {
            return Integer.parseInt(bound.trim());
        } catch (NumberFormatException e) {
            return defaultBound;
        }
    }
}
