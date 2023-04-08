package customWriter.jena;


/**
 * unmodified copy of org.apache.jena.rdfxml.xmloutput.impl.PairEntry
 *
 * Has to be copied, because the original class is package private and {@link customWriter.CustomBaseXMLWriter} needs it.
 *
 */
public class CustomPairEntry<K,V>  implements java.util.Map.Entry<K,V>  {
    K a;
    V b;
    @Override
    public boolean equals(Object o) {
        if (o != null && (o instanceof CustomPairEntry<?,?>)) {
            CustomPairEntry<?,?> e = (CustomPairEntry<?,?>) o;
            return e.a.equals(a) && e.b.equals(b);
        }
        return false;

    }
    @Override
    public K getKey() {
        return a;
    }
    @Override
    public V getValue() {
        return b;
    }
    @Override
    public int hashCode() {
        return a.hashCode() ^ b.hashCode();
    }
    @Override
    public V setValue(Object value) {
        throw new UnsupportedOperationException();
    }
    CustomPairEntry(K a, V b) {
        this.a = a;
        this.b = b;
    }

}
