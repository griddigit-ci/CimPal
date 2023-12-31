package common.customWriter.jena;

import org.apache.jena.util.iterator.Map1Iterator;
import org.apache.jena.util.iterator.WrappedIterator;

import java.util.*;

/**
 * unmodified copy of org.apache.jena.rdfxml.xmloutput.impl.Relation
 *
 * Has to be copied, because the original class is package private and {@link common.customWriter.CustomBaseXMLWriter} needs it.
 *
 */
public class CustomRelation<T> {
    final private Map<T, Set<T>> rows;
    final private Map<T, Set<T>> cols;
    final private Set<T> index;
    /** The empty Relation.
     */
    public CustomRelation() {
        rows = new HashMap<>();
        cols = new HashMap<>();
        index = new HashSet<>();
    }
    /** <code>a</code> is now related to <code>b</code>
     *
     */
    synchronized public void set(T a, T b) {
        index.add(a);
        index.add(b);
        innerAdd(rows, a, b);
        innerAdd(cols, b, a);
    }
    /** Uniquely <code>a</code> is now related to uniquely <code>b</code>.
     *
     *  When this is called any other <code>a</code> related to this <code>b</code> is removed.
     *  When this is called any other <code>b</code> related to this <code>a</code> is removed.
     *
     */
    synchronized public void set11(T a, T b) {
        clearX(a, forward(a));
        clearX(backward(b), b);
        set(a, b);
    }
    /** Uniquely <code>a</code> is now related to <code>b</code>.
     *  Many <code>b</code>'s can be related to each <code>a</code>.
     *  When this is called any other <code>a</code> related to this <code>b</code> is removed.
     */
    synchronized public void set1N(T a, T b) {
        clearX(backward(b), b);
        set(a, b);
    }
    /** <code>a</code> is now related to uniquely <code>b</code>.
     *  Many <code>a</code>'s can be related to each <code>b</code>.
     *  When this is called any other <code>b</code> related to this <code>a</code> is removed.
     */
    synchronized public void setN1(T a, T b) {
        clearX(a, forward(a));
        set(a, b);
    }
    /** <code>a</code> is now related to <code>b</code>
     *
     */
    synchronized public void setNN(T a, T b) {
        set(a, b);
    }
    /** <code>a</code> is now <em>not</em> related to <code>b</code>
     *
     */
    synchronized public void clear(T a, T b) {
        innerClear(rows, a, b);
        innerClear(cols, b, a);
    }
    private void clearX(Set<T> s, T b) {
        if (s == null)
            return;
        for ( T value : s )
        {
            clear( value, b );
        }
    }
    private void clearX(T a, Set<T> s) {
        if (s == null)
            return;
        for ( T value : s )
        {
            clear( a, value );
        }
    }
    static private <T> void innerAdd(Map<T, Set<T>> s, T a, T b) {
        Set<T> vals = s.get(a);
        if (vals == null) {
            vals = new HashSet<>();
            s.put(a, vals);
        }
        vals.add(b);
    }
    static private <T> void innerClear(Map<T, Set<T>> s, T a, T b) {
        Set<T> vals = s.get(a);
        if (vals != null) {
            vals.remove(b);
        }
    }
    /** Is <code>a</code> related to <code>b</code>?
     *
     */
    public boolean get(T a, T b) {
        Set <T> vals = rows.get(a);
        return vals != null && vals.contains(b);
    }
    /**
     * Takes this to its transitive closure.
     See B. Roy. <b>Transitivit� et connexit�.</b> <i>C.R. Acad. Sci.</i> Paris <b>249</b>, 1959 pp 216-218.
     or
     S. Warshall, <b>A theorem on Boolean matrices</b>, <i>Journal of the ACM</i>, <b>9</b>(1), 1962, pp11-12


     */
    synchronized public void transitiveClosure() {
        for ( T oj : index )
        {
            Set<T> si = cols.get( oj );
            Set<T> sk = rows.get( oj );
            if ( si != null && sk != null )
            {
                Iterator<T> i = si.iterator();
                while ( i.hasNext() )
                {
                    T oi = i.next();
                    if ( oi != oj )
                    {
                        Iterator<T> k = sk.iterator();
                        while ( k.hasNext() )
                        {
                            T ok = k.next();
                            if ( ok != oj )
                            {
                                set( oi, ok );
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * The set of <code>a</code> such that <code>a</code> is related to <code>a</code>.
     *
     */
    synchronized public Set<T> getDiagonal() {
        Set<T> rslt = new HashSet<>();
        for ( T o : index )
        {
            if ( get( o, o ) )
            {
                rslt.add( o );
            }
        }
        return rslt;
    }

    synchronized public CustomRelation<T> copy() {
        CustomRelation<T> rslt = new CustomRelation<>();
        Iterator<CustomPairEntry<T, T>> it = iterator();
        while ( it.hasNext() ) {
            Map.Entry<T, T> e = it.next();
            rslt.set(e.getKey(),e.getValue());
        }
        return rslt;
    }

    /**
     * The set of <code>b</code> such that <code>a</code> is related to <code>b</code>.
     *
     */
    public Set<T> forward(T a) {
        return rows.get(a);
    }
    /**
     * The set of <code>a</code> such that <code>a</code> is related to <code>b</code>.
     *
     */
    public Set<T> backward(T b) {
        return cols.get(b);
    }

    private static <T> Iterator<CustomPairEntry<T, T>> pairEntry(Map.Entry<T, Set<T>> pair)
    {
        final T a = pair.getKey() ;
        Set<T> bs = pair.getValue() ;
        return new Map1Iterator<>(b -> new CustomPairEntry<>(a, b), bs.iterator()) ;
    }

    /**
     * An Iterator over the pairs of the Relation.
     * Each pair is returned as a java.util.Map.Entry.
     * The first element is accessed through <code>getKey()</code>,
     * the second through <code>getValue()</code>.
     *@see java.util.Map.Entry
     */
    public Iterator<CustomPairEntry<T, T>> iterator()
    {
        Map1Iterator<Map.Entry<T, Set<T>>,Iterator<CustomPairEntry<T, T>>> iter1 =
                new Map1Iterator<>( entry -> pairEntry(entry) , rows.entrySet().iterator()) ;
        // And now flatten it.
        Iterator<CustomPairEntry<T, T>> iter2 = WrappedIterator.createIteratorIterator(iter1) ;
        return iter2 ;
    }
}
