import com.datacube.export.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

public final class XlsxTextDiagnosis {
    record Sample(String id, String input, String expectedRaw, boolean reject) {}
    static String hex(String s) {
        var out = new StringBuilder();
        for (int i=0;i<s.length();i++) { if (i>0) out.append(','); out.append(String.format("%04X",(int)s.charAt(i))); }
        return out.toString();
    }
    // Independent, one-pass ST_Xstring reader; generated escapes are not recursively decoded.
    static String decode(String text) {
        var matcher=Pattern.compile("_x([0-9a-fA-F]{4})_").matcher(text);
        var decoded=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(decoded,Matcher.quoteReplacement(
                String.valueOf((char)Integer.parseInt(matcher.group(1),16))));
        matcher.appendTail(decoded); return decoded.toString();
    }
    static DocumentBuilder parser() throws Exception {
        var f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var b=f.newDocumentBuilder(); b.setErrorHandler(new DefaultHandler(){
            @Override public void fatalError(SAXParseException e)throws SAXException{throw e;}
        }); return b;
    }
    public static void main(String[] args) throws Exception {
        Path evidence=Path.of(args[0]), owned=Path.of(args[1]);
        var samples=List.of(
            new Sample("nul","a\u0000b","a_x0000_b",false),
            new Sample("c0","a\u0001\u000B\u001Fb","a_x0001__x000B__x001F_b",false),
            new Sample("cr","a\rb\r\nc","a_x000D_b_x000D_\nc",false),
            new Sample("fffe","a\uFFFEb","a_xFFFE_b",false),
            new Sample("ffff","a\uFFFFb","a_xFFFF_b",false),
            new Sample("literal","p_x0000__x000D__x005F_x0000__x00aF_q",
                    "p_x005F_x0000__x005F_x000D__x005F_x005F_x005F_x0000__x005F_x00aF_q",false),
            new Sample("near-literal","_X0000_ _x000g_ _x000_ _x00000_ _x005F_",
                    "_X0000_ _x000g_ _x000_ _x00000_ _x005F_x005F_",false),
            new Sample("high-surrogate","a\uD800b",null,true),
            new Sample("low-surrogate","a\uDC00b",null,true),
            new Sample("ordinary"," 中文<&>\"\t\n\u007F\u0080\u009F\uD7FF\uE000\uFFFD\uD800\uDC00\uD83D\uDE00\uDBFF\uDFFF ",
                    " 中文<&>\"\t\n\u007F\u0080\u009F\uD7FF\uE000\uFFFD\uD800\uDC00\uD83D\uDE00\uDBFF\uDFFF ",false));
        int failures=0; var manifest=new ArrayList<String>();
        for(var sample:samples){
            Path target=owned.resolve(sample.id()+".xlsx"); Files.writeString(target,"OLD");
            var operation=new ResultExportOperation();
            try{
                var snapshot=ResultExportSnapshot.capture(QueryResult.query(List.of(sample.input()),
                    List.of(List.of((Object)sample.input())),1),"select synthetic_value",List.of(0),
                    List.of(new ResultExportSnapshot.Column(0,sample.input())));
                new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target),operation,
                    (temporary,token)->QueryResultFileWriter.write(temporary,QueryResultFileWriter.Format.XLSX,
                        snapshot,ResultExportScope.CURRENT_FILTERED,false,null,token));
                Files.copy(target,evidence.resolve(sample.id()+".xlsx"));
                try(var zip=new ZipFile(target.toFile())){
                    byte[] raw=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes();
                    Files.write(evidence.resolve(sample.id()+"-sheet.xml"),raw);
                    try{
                        var document=parser().parse(new java.io.ByteArrayInputStream(raw));
                        var texts=document.getElementsByTagNameNS("http://schemas.openxmlformats.org/spreadsheetml/2006/main","t");
                        boolean exact=!sample.reject(),mapping=!sample.reject();
                        for(int i=0;i<texts.getLength();i++){
                            String text=texts.item(i).getTextContent();
                            exact &= sample.input().equals(decode(text)); mapping &= Objects.equals(sample.expectedRaw(),text);
                            System.out.println(sample.id()+" cell="+i+" rawHex="+hex(text)+" decodedHex="+hex(decode(text)));
                        }
                        boolean pass=exact&&mapping&&texts.getLength()==2;
                        if(!pass) failures++;
                        System.out.println(sample.id()+" published="+operation.published()+" parse=true exact="+exact+" mapping="+mapping+" pass="+pass);
                    }catch(SAXParseException invalid){
                        failures++;System.out.println(sample.id()+" published="+operation.published()+" parse=false pass=false");
                    }
                }
            }catch(Exception rejected){
                boolean pass=sample.reject()&&!operation.published()&&"OLD".equals(Files.readString(target));
                if(!pass)failures++;
                System.out.println(sample.id()+" published="+operation.published()+" rejectedType="+rejected.getClass().getName()+" oldPreserved="+"OLD".equals(Files.readString(target))+" pass="+pass);
            } finally {
                manifest.add("{\"id\":\""+sample.id()+"\",\"inputUtf16Hex\":\""+hex(sample.input())
                    +"\",\"expectedRawUtf16Hex\":"+(sample.expectedRaw()==null?"null":"\""+hex(sample.expectedRaw())+"\"")
                    +",\"cells\":[\"A1\",\"A2\"],\"styled\":true,\"target\":\""+sample.id()+".xlsx\",\"expectReject\":"+sample.reject()
                    +",\"published\":"+operation.published()+"}");
            }
        }
        Files.writeString(evidence.resolve("cases.json"),"[\n"+String.join(",\n",manifest)+"\n]\n");
        System.out.println("DIAGNOSIS_FAILURES="+failures); if(failures>0)System.exit(1);
    }
}

