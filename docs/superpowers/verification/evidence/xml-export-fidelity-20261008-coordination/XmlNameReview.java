import com.datacube.export.ResultExporter;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.*;
import java.util.*;
import org.xml.sax.InputSource;
public class XmlNameReview {
 public static void main(String[] args) throws Exception {
  var f=DocumentBuilderFactory.newInstance();
  f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
  f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
  var p=f.newDocumentBuilder();
  for(int c:new int[]{0x37F,0x8A0,0x9FE,0x4E2D,0xAA}) {
   String n=Character.toString(c); var w=new StringWriter();
   ResultExporter.writeXml(w,List.of(n),List.of(List.of("SYNTHETIC")));
   try{p.parse(new InputSource(new StringReader(w.toString())));System.out.println(Integer.toHexString(c)+" parsed=true");}
   catch(Exception e){System.out.println(Integer.toHexString(c)+" parsed=false");}
  }
 }
}