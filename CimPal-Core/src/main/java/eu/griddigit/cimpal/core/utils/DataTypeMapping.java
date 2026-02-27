package eu.griddigit.cimpal.core.utils;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.datatypes.xsd.impl.XSDDateTimeStampType;

public class DataTypeMapping {
    //Support methog to identify the datatypes in cases where the mapping information comes from a saved mapping file
    public static RDFDatatype mapFromMapDefaultFile(String value){
        RDFDatatype valueRDFdatatype = null;
        if (value.equals(XSDDatatype.XSDinteger.toString())) {
            valueRDFdatatype = XSDDatatype.XSDinteger;
        } else if (value.equals(XSDDatatype.XSDfloat.toString())){
            valueRDFdatatype = XSDDatatype.XSDfloat;
        } else if (value.equals(XSDDatatype.XSDstring.toString())){
            valueRDFdatatype = XSDDatatype.XSDstring;
        } else if (value.equals(XSDDatatype.XSDboolean.toString())){
            valueRDFdatatype = XSDDatatype.XSDboolean;
        } else if (value.equals(XSDDatatype.XSDdate.toString())){
            valueRDFdatatype = XSDDatatype.XSDdate;
        } else if (value.equals(XSDDatatype.XSDdateTime.toString())){
            valueRDFdatatype = XSDDatatype.XSDdateTime;
        } else if (value.equals(XSDDateTimeStampType.XSDdateTimeStamp.toString())){
            valueRDFdatatype = XSDDateTimeStampType.XSDdateTimeStamp;
        } else if (value.equals(XSDDatatype.XSDdecimal.toString())){
            valueRDFdatatype = XSDDatatype.XSDdecimal;
        } else if (value.equals(XSDDatatype.XSDduration.toString())){
            valueRDFdatatype = XSDDatatype.XSDduration;
        } else if (value.equals(XSDDatatype.XSDgMonthDay.toString())){
            valueRDFdatatype = XSDDatatype.XSDgMonthDay;
        } else if (value.equals(XSDDatatype.XSDtime.toString())) {
            valueRDFdatatype = XSDDatatype.XSDtime;
        } else if (value.equals(XSDDatatype.XSDanyURI.toString())) {
            valueRDFdatatype = XSDDatatype.XSDanyURI;
        } else if (value.equals(RDFLangString.rdfLangString.toString())) {
            valueRDFdatatype = RDFLangString.rdfLangString;
        }

        return valueRDFdatatype;
    }
}
