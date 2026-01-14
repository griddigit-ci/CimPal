package eu.griddigit.cimpal.main.application.PssePFcompare;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.GuiHelper;
import javafx.stage.FileChooser;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import eu.griddigit.cimpal.main.util.ExcelTools;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class comparePssePF {

    public static void comparePssePFresults() {

        Map<String, Double> psseNodesUmap = new HashMap<>();
        Map<String, Double> pfNodesUmap = new HashMap<>();
        Map<String, List<Double>> psseLinesPmap = new HashMap<>();
        Map<String, List<Double>> psseLinesQmap = new HashMap<>();
        Map<String, List<Double>> pfLinesPmap = new HashMap<>();
        Map<String, List<Double>> pfLinesQmap = new HashMap<>();
        Map<String, Integer> psseNodesType = new HashMap<>();

        Map<String, List<Double>> psseTrafoPmap = new HashMap<>();
        Map<String, List<Double>> psseTrafoQmap = new HashMap<>();
        Map<String, List<Double>> pfTrafoPmap = new HashMap<>();
        Map<String, List<Double>> pfTrafoQmap = new HashMap<>();

        //process PSSE voltage
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for nodes PSS/E", "*.xlsx"));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file = filechooser.showOpenDialog(null);

        if (file!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file.toString(),0);


            int count=0;
            for (int i=1; i<inputXLSdata.size(); i++){
                String nodeName = ((LinkedList) inputXLSdata.get(i)).get(0).toString().substring(0,8);
                Double voltageKv = (Double) ((LinkedList) inputXLSdata.get(i)).get(1);
                Double nodetype = (Double) ((LinkedList) inputXLSdata.get(i)).get(3);
                //Float voltageDeg = ((LinkedList) inputXLSdata.get(i)).get(2);
                psseNodesUmap.putIfAbsent(nodeName,voltageKv);
                psseNodesType.putIfAbsent(nodeName,nodetype.intValue());
                count = count+1;
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PSS/E nodes count: "+count, true);
        }

        //process PF voltage
        //select file
        FileChooser filechooser1 = new FileChooser();
        filechooser1.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for nodes PowerFactory", "*.xlsx"));
        filechooser1.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file1 = filechooser1.showOpenDialog(null);

        int countPF=0;
        int count1=0;
        if (file1!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file1.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file1.toString(),0);



            for (int i=1; i<inputXLSdata.size(); i++){
                String nodeName = ((LinkedList) inputXLSdata.get(i)).get(0).toString();
                if (nodeName.length()<8){
                    nodeName=nodeName+"        ";
                    nodeName=nodeName.substring(0,8);
                    count1=count1+1;
                }
                Double voltageKv = (Double) ((LinkedList) inputXLSdata.get(i)).get(1);
                //Float voltageDeg = ((LinkedList) inputXLSdata.get(i)).get(2);
                pfNodesUmap.putIfAbsent(nodeName,voltageKv);
                countPF=countPF+1;
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PowerFactory nodes count: "+countPF, true);
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PowerFactory nodes with less that 8 chars count: "+count1, true);
        }


        //do the voltage compare
        List<String> nodeNameList = new LinkedList<>(); // list of nodes
        List<Double> psseVoltageList = new LinkedList<>(); // list of psse volatges
        List<Double> pfVoltageList = new LinkedList<>(); // list of pf voltages
        List<Double> voltageDiffList = new LinkedList<>(); // list of pf voltages
        int count=0;
        count1=0;
        int count2=0;
        int count3=0;
        int count2t4=0;
        for (Map.Entry<String,Double> entry : psseNodesUmap.entrySet()) {
            if (pfNodesUmap.containsKey(entry.getKey())) {
                if (pfNodesUmap.get(entry.getKey())!=0) {
                    if (psseNodesType.get(entry.getKey())!=4){
                        nodeNameList.add(entry.getKey());
                        psseVoltageList.add(entry.getValue());
                        pfVoltageList.add(pfNodesUmap.get(entry.getKey()));
                        voltageDiffList.add(Math.abs(entry.getValue() - pfNodesUmap.get(entry.getKey())));
                        count2 = count2 + 1;
                    }else{
                        count2t4=count2t4+1;
                    }
                }else{
                   count3=count3+1;
                }
                count1=count1+1;
            }
            count=count+1;
        }
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PSS/E nodes to compare count: "+count, true);
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Matching node names count: "+count1, true);
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Values in PF different than 0 kV, count: "+count2, true);
        if (count-count3-count2t4==count2 && count==countPF && count1==count){
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Count is matching", true);
        }else{
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Analyse counts. Maybe an issue.", true);
        }
        // do the excel export
        exportResultVoltage(nodeNameList,psseVoltageList,pfVoltageList,voltageDiffList,"Voltage","VoltageComparison","Save Voltage Comparison");
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Ready", true);


        //do compare for line flow

        //select file
        FileChooser filechooser2 = new FileChooser();
        filechooser2.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for lines PSS/E", "*.xlsx"));
        filechooser2.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file2 = filechooser2.showOpenDialog(null);

        if (file2!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file2.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file2.toString(),0);


            int count01=0;
            for (int i=1; i<inputXLSdata.size(); i++){
                if (((Double) ((LinkedList) inputXLSdata.get(i)).get(3)).intValue()==1) {
                    String nodeName1 = ((LinkedList) inputXLSdata.get(i)).get(0).toString().substring(0, 8);
                    String nodeName2 = ((LinkedList) inputXLSdata.get(i)).get(1).toString().substring(0, 8);
                    Double lineP = (Double) ((LinkedList) inputXLSdata.get(i)).get(4);
                    Double lineQ = (Double) ((LinkedList) inputXLSdata.get(i)).get(5);
                    //psseLinesPmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineP);
                    //psseLinesQmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineQ);
                    List<Double> linePlist;
                    if (psseLinesPmap.containsKey(nodeName1 + "-" + nodeName2)){
                        linePlist = psseLinesPmap.get(nodeName1 + "-" + nodeName2);
                        linePlist.add(lineP);
                        psseLinesPmap.replace(nodeName1 + "-" + nodeName2, linePlist);
                    }else{
                        if (!psseLinesPmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            linePlist = new LinkedList<>();
                            linePlist.add(lineP);
                            psseLinesPmap.putIfAbsent(nodeName1 + "-" + nodeName2, linePlist);
                        }else{
                            linePlist = psseLinesPmap.get(nodeName2 + "-" + nodeName1);
                            linePlist.add(-lineP);
                            psseLinesPmap.replace(nodeName2 + "-" + nodeName1, linePlist);
                        }
                    }

                    List<Double> lineQlist;
                    if (psseLinesQmap.containsKey(nodeName1 + "-" + nodeName2)){
                        lineQlist = psseLinesQmap.get(nodeName1 + "-" + nodeName2);
                        lineQlist.add(lineQ);
                        psseLinesQmap.replace(nodeName1 + "-" + nodeName2, lineQlist);
                    }else{
                        if (!psseLinesQmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            lineQlist = new LinkedList<>();
                            lineQlist.add(lineQ);
                            psseLinesQmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineQlist);
                        }else{
                            lineQlist = psseLinesQmap.get(nodeName2 + "-" + nodeName1);
                            lineQlist.add(-lineQ);
                            psseLinesQmap.replace(nodeName2 + "-" + nodeName1, lineQlist);
                        }
                    }


                    //psseLinesPmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineP);
                    //psseLinesQmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineQ);
                    count01 = count01 + 1;
                }
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PSS/E lines count: "+count01, true);
        }

        //process PF lines
        //select file
        FileChooser filechooser3 = new FileChooser();
        filechooser3.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for lines PowerFactory", "*.xlsx"));
        filechooser3.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file3 = filechooser3.showOpenDialog(null);

        int countPFline=0;
        if (file3!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file3.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file3.toString(),0);



            for (int i=1; i<inputXLSdata.size(); i++){
                //int check=((LinkedList) inputXLSdata.get(i)).size();
                //GuiHelper.appendTextToOutputWindow("check: "+check+", i="+i, true);
                if (((Double) ((LinkedList) inputXLSdata.get(i)).get(4)).intValue()==0) {
                    String nodeName1 = ((LinkedList) inputXLSdata.get(i)).get(0).toString();
                    nodeName1=nodeName1.substring(2);
                    if (nodeName1.length()<8){
                        nodeName1=nodeName1+"        ";
                        nodeName1=nodeName1.substring(0,8);
                    }
                    String nodeName2 = ((LinkedList) inputXLSdata.get(i)).get(1).toString();
                    nodeName2=nodeName2.substring(2);
                    if (nodeName2.length()<8){
                        nodeName2=nodeName2+"        ";
                        nodeName2=nodeName2.substring(0,8);
                    }
                    Double lineP = (Double) ((LinkedList) inputXLSdata.get(i)).get(2);
                    Double lineQ = (Double) ((LinkedList) inputXLSdata.get(i)).get(3);
                    //pfLinesPmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineP);
                    //pfLinesQmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineQ);

                    List<Double> linePlist;
                    if (pfLinesPmap.containsKey(nodeName1 + "-" + nodeName2)) {
                        linePlist = pfLinesPmap.get(nodeName1 + "-" + nodeName2);
                        linePlist.add(lineP);
                        pfLinesPmap.replace(nodeName1 + "-" + nodeName2, linePlist);
                    }else{
                        if (!pfLinesPmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            linePlist = new LinkedList<>();
                            linePlist.add(lineP);
                            pfLinesPmap.putIfAbsent(nodeName1 + "-" + nodeName2, linePlist);
                        }else{
                            linePlist = pfLinesPmap.get(nodeName2 + "-" + nodeName1);
                            linePlist.add(-lineP);
                            pfLinesPmap.replace(nodeName2 + "-" + nodeName1, linePlist);
                        }
                    }

                    List<Double> lineQlist;
                    if (pfLinesQmap.containsKey(nodeName1 + "-" + nodeName2)){
                        lineQlist = pfLinesQmap.get(nodeName1 + "-" + nodeName2);
                        lineQlist.add(lineQ);
                        pfLinesQmap.replace(nodeName1 + "-" + nodeName2, lineQlist);
                    }else{
                        if (!pfLinesQmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            lineQlist = new LinkedList<>();
                            lineQlist.add(lineQ);
                            pfLinesQmap.putIfAbsent(nodeName1 + "-" + nodeName2, lineQlist);
                        }else{
                            lineQlist = pfLinesQmap.get(nodeName2 + "-" + nodeName1);
                            lineQlist.add(-lineQ);
                            pfLinesQmap.replace(nodeName2 + "-" + nodeName1, lineQlist);
                        }
                    }



                    //pfLinesPmap.put(nodeName1 + "-" + nodeName2, lineP);
                    //pfLinesQmap.put(nodeName1 + "-" + nodeName2, lineQ);
                }

                countPFline=countPFline+1;
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PowerFactory lines count: "+countPFline, true);
            //GuiHelper.appendTextToOutputWindow("PowerFactory nodes with less that 8 chars count: "+count1line, true);
        }


        //do the lines compare
        List<String> lineNameList = new LinkedList<>();
        List<Double> psselinePList = new LinkedList<>();
        List<Double> pfLinePList = new LinkedList<>();
        List<Double> linePdiffList = new LinkedList<>();
        List<Double> psselineQList = new LinkedList<>();
        List<Double> pfLineQList = new LinkedList<>();
        List<Double> lineQdiffList = new LinkedList<>();

        for (Map.Entry<String,List<Double>> entry : psseLinesPmap.entrySet()) {
            //if (entry.getKey().equals("D2OBA 53-D2OBA 56")){
            //    int kk=1;
            //}
            if (pfLinesPmap.containsKey(entry.getKey())) {


                List<Double> plist=entry.getValue();
                List<Double> qlist=psseLinesQmap.get(entry.getKey());
                if (plist.size()!=1){
                    int listcount=0;
                    for (Double pl : plist) {
                        lineNameList.add(entry.getKey());
                        psselinePList.add(pl);
                        double pfclosest=getClosestNumber(pfLinesPmap.get(entry.getKey()),pl);
                        pfLinePList.add(pfclosest);
                        linePdiffList.add(Math.abs(Math.abs(pl) - Math.abs(pfclosest)));

                        psselineQList.add(qlist.get(listcount));
                        pfclosest=getClosestNumber(pfLinesQmap.get(entry.getKey()),qlist.get(listcount));
                        pfLineQList.add(pfclosest);
                        lineQdiffList.add(Math.abs(Math.abs(qlist.get(listcount)) - Math.abs(pfclosest)));
                        listcount=listcount+1;
                    }
                }else{
                    lineNameList.add(entry.getKey());
                    psselinePList.add(entry.getValue().get(0));
                    pfLinePList.add(pfLinesPmap.get(entry.getKey()).get(0));
                    linePdiffList.add(Math.abs(Math.abs(entry.getValue().get(0)) - Math.abs(pfLinesPmap.get(entry.getKey()).get(0))));

                    psselineQList.add(psseLinesQmap.get(entry.getKey()).get(0));
                    pfLineQList.add(pfLinesQmap.get(entry.getKey()).get(0));
                    lineQdiffList.add(Math.abs(Math.abs(psseLinesQmap.get(entry.getKey()).get(0)) - Math.abs(pfLinesQmap.get(entry.getKey()).get(0))));
                }
            }else{

                if (!pfLinesPmap.containsKey(entry.getKey().substring(9,17)+"-"+entry.getKey().substring(0,8))) {
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Line not in PowerFactory list: " + entry.getKey(), true);
                }
            }


        }

        int cpss=0;
        for (Map.Entry<String,List<Double>> entry : pfLinesPmap.entrySet()) {
            if (psseLinesPmap.containsKey(entry.getKey())) {
                cpss=cpss+1;
            }else{
                if (!psseLinesPmap.containsKey(entry.getKey().substring(9,17)+"-"+entry.getKey().substring(0,8))) {
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Line not in PSS/E list: " + entry.getKey(), true);
                }
            }
        }
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Count of match between PSS/E and PF: " + cpss, true);

        // do the excel export
        exportResultLines(lineNameList,psselinePList,pfLinePList,linePdiffList,psselineQList,pfLineQList,lineQdiffList,"Lines","LinesComparison","Save Lines Comparison");
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Ready", true);






        //do compare for trafo flow

        //process PSSE voltage
        //select file
        FileChooser filechooser4 = new FileChooser();
        filechooser4.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for trafo PSS/E", "*.xlsx"));
        filechooser4.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file4 = filechooser4.showOpenDialog(null);

        if (file4!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file4.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file4.toString(),0);


            int count01=0;
            for (int i=1; i<inputXLSdata.size(); i++){
                if (((Double) ((LinkedList) inputXLSdata.get(i)).get(3)).intValue()==1) {
                    String nodeName1 = ((LinkedList) inputXLSdata.get(i)).get(0).toString().substring(0, 8);
                    String nodeName2 = ((LinkedList) inputXLSdata.get(i)).get(1).toString().substring(0, 8);
                    Double trafoP = (Double) ((LinkedList) inputXLSdata.get(i)).get(4);
                    Double trafoQ = (Double) ((LinkedList) inputXLSdata.get(i)).get(5);
                    //psseTrafoPmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoP);
                    //psseTrafoQmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoQ);

                    List<Double> trafoPlist;
                    if (psseTrafoPmap.containsKey(nodeName1 + "-" + nodeName2)){
                        trafoPlist = psseTrafoPmap.get(nodeName1 + "-" + nodeName2);
                        trafoPlist.add(trafoP);
                        psseTrafoPmap.replace(nodeName1 + "-" + nodeName2, trafoPlist);
                    }else{
                        if (!psseTrafoPmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            trafoPlist = new LinkedList<>();
                            trafoPlist.add(trafoP);
                            psseTrafoPmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoPlist);
                        }else{
                            trafoPlist = psseTrafoPmap.get(nodeName2 + "-" + nodeName1);
                            trafoPlist.add(-trafoP);
                            psseTrafoPmap.replace(nodeName2 + "-" + nodeName1, trafoPlist);
                        }
                    }

                    List<Double> trafoQlist;
                    if (psseTrafoQmap.containsKey(nodeName1 + "-" + nodeName2)){
                        trafoQlist = psseTrafoQmap.get(nodeName1 + "-" + nodeName2);
                        trafoQlist.add(trafoQ);
                        psseTrafoQmap.replace(nodeName1 + "-" + nodeName2, trafoQlist);
                    }else{
                        if (!psseTrafoQmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            trafoQlist = new LinkedList<>();
                            trafoQlist.add(trafoQ);
                            psseTrafoQmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoQlist);
                        }else{
                            trafoQlist = psseTrafoQmap.get(nodeName2 + "-" + nodeName1);
                            trafoQlist.add(-trafoQ);
                            psseTrafoQmap.replace(nodeName2 + "-" + nodeName1, trafoQlist);
                        }
                    }



                    count01 = count01 + 1;
                }
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PSS/E trafo count: "+count01, true);
        }

        //process PF trafo
        //select file
        FileChooser filechooser5 = new FileChooser();
        filechooser5.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select files for trafo PowerFactory", "*.xlsx"));
        filechooser5.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file5 = filechooser5.showOpenDialog(null);

        int countPFtrafo=0;
        if (file5!=null) {// the file is selected
            MainController.prefs.put("LastWorkingFolder", file5.getParent());

            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file5.toString(),0);



            for (int i=1; i<inputXLSdata.size(); i++){
                if (((Double) ((LinkedList) inputXLSdata.get(i)).get(4)).intValue()==0) {
                    String nodeName1 = ((LinkedList) inputXLSdata.get(i)).get(0).toString();
                    nodeName1=nodeName1.substring(2);
                    if (nodeName1.length()<8){
                        nodeName1=nodeName1+"        ";
                        nodeName1=nodeName1.substring(0,8);
                    }
                    String nodeName2 = ((LinkedList) inputXLSdata.get(i)).get(1).toString();
                    nodeName2=nodeName2.substring(2);
                    if (nodeName2.length()<8){
                        nodeName2=nodeName2+"        ";
                        nodeName2=nodeName2.substring(0,8);
                    }
                    Double trafoP = (Double) ((LinkedList) inputXLSdata.get(i)).get(2);
                    Double trafoQ = (Double) ((LinkedList) inputXLSdata.get(i)).get(3);
                    //pfTrafoPmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoP);
                    //pfTrafoQmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoQ);

                    List<Double> trafoPlist;
                    if (pfTrafoPmap.containsKey(nodeName1 + "-" + nodeName2)){
                        trafoPlist = pfTrafoPmap.get(nodeName1 + "-" + nodeName2);
                        trafoPlist.add(trafoP);
                        pfTrafoPmap.replace(nodeName1 + "-" + nodeName2, trafoPlist);
                    }else{
                        if (!pfTrafoPmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            trafoPlist = new LinkedList<>();
                            trafoPlist.add(trafoP);
                            pfTrafoPmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoPlist);
                        }else{
                            trafoPlist = pfTrafoPmap.get(nodeName2 + "-" + nodeName1);
                            trafoPlist.add(-trafoP);
                            pfTrafoPmap.replace(nodeName2 + "-" + nodeName1, trafoPlist);
                        }
                    }

                    List<Double> trafoQlist;
                    if (pfTrafoQmap.containsKey(nodeName1 + "-" + nodeName2)){
                        trafoQlist = pfTrafoQmap.get(nodeName1 + "-" + nodeName2);
                        trafoQlist.add(trafoQ);
                        pfTrafoQmap.replace(nodeName1 + "-" + nodeName2, trafoQlist);
                    }else{
                        if (!pfTrafoQmap.containsKey(nodeName2 + "-" + nodeName1)) {
                            trafoQlist = new LinkedList<>();
                            trafoQlist.add(trafoQ);
                            pfTrafoQmap.putIfAbsent(nodeName1 + "-" + nodeName2, trafoQlist);
                        }else{
                            trafoQlist = pfTrafoQmap.get(nodeName2 + "-" + nodeName1);
                            trafoQlist.add(-trafoQ);
                            pfTrafoQmap.replace(nodeName2 + "-" + nodeName1, trafoQlist);
                        }
                    }
                }

                countPFtrafo=countPFtrafo+1;
            }
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"PowerFactory trafo count: "+countPFtrafo, true);
            //GuiHelper.appendTextToOutputWindow("PowerFactory nodes with less that 8 chars count: "+count1line, true);
        }


        //do the trafo compare
        List<String> trafoNameList = new LinkedList<>();
        List<Double> pssetrafoPList = new LinkedList<>();
        List<Double> pftrafoPList = new LinkedList<>();
        List<Double> trafoPdiffList = new LinkedList<>();
        List<Double> pssetrafoQList = new LinkedList<>();
        List<Double> pftrafoQList = new LinkedList<>();
        List<Double> trafoQdiffList = new LinkedList<>();
        //int count=0;
        //count1=0;
        //int count2=0;
        for (Map.Entry<String,List<Double>> entry : psseTrafoPmap.entrySet()) {
            if (pfTrafoPmap.containsKey(entry.getKey())) {


                //pssetrafoPList.add(entry.getValue());
                //pftrafoPList.add(pfTrafoPmap.get(entry.getKey()));
                //trafoPdiffList.add(Math.abs(Math.abs(entry.getValue()) - Math.abs(pfTrafoPmap.get(entry.getKey()))));

                //pssetrafoQList.add(psseTrafoQmap.get(entry.getKey()));
                //pftrafoQList.add(pfTrafoQmap.get(entry.getKey()));
                //trafoQdiffList.add(Math.abs(Math.abs(psseTrafoQmap.get(entry.getKey())) - Math.abs(pfTrafoQmap.get(entry.getKey()))));


                List<Double> plist=entry.getValue();
                List<Double> qlist=psseTrafoQmap.get(entry.getKey());
                if (plist.size()!=1){
                    int listcount=0;
                    for (Double pl : plist) {
                        trafoNameList.add(entry.getKey());
                        pssetrafoPList.add(pl);
                        double pfclosest=getClosestNumber(pfTrafoPmap.get(entry.getKey()),pl);
                        pftrafoPList.add(pfclosest);
                        trafoPdiffList.add(Math.abs(Math.abs(pl) - Math.abs(pfclosest)));

                        pssetrafoQList.add(qlist.get(listcount));
                        pfclosest=getClosestNumber(pfTrafoQmap.get(entry.getKey()),qlist.get(listcount));
                        pftrafoQList.add(pfclosest);
                        trafoQdiffList.add(Math.abs(Math.abs(qlist.get(listcount)) - Math.abs(pfclosest)));
                        listcount=listcount+1;
                    }
                }else{
                    trafoNameList.add(entry.getKey());
                    pssetrafoPList.add(entry.getValue().get(0));
                    pftrafoPList.add(pfTrafoPmap.get(entry.getKey()).get(0));
                    trafoPdiffList.add(Math.abs(Math.abs(entry.getValue().get(0)) - Math.abs(pfTrafoPmap.get(entry.getKey()).get(0))));

                    pssetrafoQList.add(psseTrafoQmap.get(entry.getKey()).get(0));
                    pftrafoQList.add(pfTrafoQmap.get(entry.getKey()).get(0));
                    trafoQdiffList.add(Math.abs(Math.abs(psseTrafoQmap.get(entry.getKey()).get(0)) - Math.abs(pfTrafoQmap.get(entry.getKey()).get(0))));
                }

            }
        }

        // do the excel export
        exportResultLines(trafoNameList,pssetrafoPList,pftrafoPList,trafoPdiffList,pssetrafoQList,pftrafoQList,trafoQdiffList,"Trafo","TrafoComparison","Save Trafo Comparison");
        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Ready", true);



    }

    private static void exportResultVoltage(List nodeNameList, List psseVoltageList, List pfVoltageList, List VoltageDiffList, String sheetname, String initialFileName, String title) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Node name");
        firstRow.createCell(1).setCellValue("PSS/E voltage, Kv");
        firstRow.createCell(2).setCellValue("PF voltage, Kv");
        firstRow.createCell(3).setCellValue("Absolute difference");

        for (int row=0; row<nodeNameList.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = nodeNameList.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = psseVoltageList.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = pfVoltageList.get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = VoltageDiffList.get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }


        }
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Result files", "*.xlsx"));
        filechooser.setInitialFileName(initialFileName);
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        filechooser.setTitle(title);
        File saveFile = filechooser.showSaveDialog(null);
        if (saveFile != null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
                outputStream.flush();
                outputStream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void exportResultLines(List lineNameList, List psselinePList, List pfLinePList, List linePdiffList, List psselineQList, List pfLineQList, List lineQdiffList, String sheetname, String initialFileName, String title) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Line name");
        firstRow.createCell(1).setCellValue("PSS/E line P");
        firstRow.createCell(2).setCellValue("PF line P");
        firstRow.createCell(3).setCellValue("Absolute difference for P");
        firstRow.createCell(4).setCellValue("PSS/E line Q");
        firstRow.createCell(5).setCellValue("PF line Q");
        firstRow.createCell(6).setCellValue("Absolute difference for Q");

        for (int row=0; row<lineNameList.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = lineNameList.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = psselinePList.get(row);
            try {
                if (celValue1 != null ) { //&& Double.parseDouble(celValue1.toString()) != 0.0
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = pfLinePList.get(row);
            try {
                if (celValue2 != null ) { //&& Double.parseDouble(celValue2.toString()) != 0.0
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = linePdiffList.get(row);
            try {
                if (celValue3 != null ) { //&& Double.parseDouble(celValue3.toString()) != 0.0
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }

            Object celValue4 = psselineQList.get(row);
            try {
                if (celValue4 != null ) { //&& Double.parseDouble(celValue4.toString()) != 0.0
                    xssfRow.createCell(4).setCellValue(Double.parseDouble(celValue4.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(4).setCellValue(celValue4.toString());
            }

            Object celValue5 = pfLineQList.get(row);
            try {
                if (celValue5 != null ) { //&& Double.parseDouble(celValue5.toString()) != 0.0
                    xssfRow.createCell(5).setCellValue(Double.parseDouble(celValue5.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(5).setCellValue(celValue5.toString());
            }

            Object celValue6 = lineQdiffList.get(row);
            try {
                if (celValue6 != null ) { //&& Double.parseDouble(celValue6.toString()) != 0.0
                    xssfRow.createCell(6).setCellValue(Double.parseDouble(celValue6.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(6).setCellValue(celValue6.toString());
            }


        }
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Result files", "*.xlsx"));
        filechooser.setInitialFileName(initialFileName);
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        filechooser.setTitle(title);
        File saveFile = filechooser.showSaveDialog(null);
        if (saveFile != null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
                outputStream.flush();
                outputStream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static Double getClosestNumber(List<Double> list, Double number) {

        double distance = Math.abs(list.get(0) - number);
        int idx = 0;
        for(int c = 1; c < list.size(); c++){
            double cdistance = Math.abs(list.get(c) - number);
            if(cdistance < distance){
                idx = c;
                distance = cdistance;
            }
        }
        return list.get(idx);
    }


}
