package com.ociweb.jfast.loader;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;

import javax.xml.parsers.ParserConfigurationException;

import org.junit.Test;
import org.xml.sax.SAXException;

import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.primitive.FASTInput;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTInputStream;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArray;
import com.ociweb.jfast.stream.FASTDynamicReader;
import com.ociweb.jfast.stream.FASTDynamicWriter;
import com.ociweb.jfast.stream.FASTReaderDispatch;
import com.ociweb.jfast.stream.FASTRingBuffer;

public class TemplateLoaderTest {

	@Test
	public void buildRawCatalog() {
		
		byte[] catalogByteArray = buildRawCatalogData();
		
        //reconstruct Catalog object from stream		
		FASTInput input = new FASTInputByteArray(catalogByteArray);
		TemplateCatalog catalog = new TemplateCatalog(new PrimitiveReader(input));
		
		boolean ok = false;
		int[] script = null;
		try{
			// /performance/example.xml contains 3 templates.
			assertEquals(3, catalog.templatesCount());
			assertEquals(496, catalogByteArray.length);
			
			script = catalog.fullScript();
			assertEquals(46, script.length);
			//TODO: need better tests.
		//	assertEquals(1128, (script[0]>>32));//First Id
			
			//CMD:Group:010000/Close:PMap::010001/9
			//assertEquals(0xC110_0009l,0xFFFFFFFFl&script[script.length-1]);//Last Token
			ok = true;
		} finally {
			if (!ok) {
				System.err.println("Script Details:");
				if (null!=script) {
					System.err.println(convertScriptToString(script));
				}
			}
		}
	}

	private String convertScriptToString(int[] script) {
		StringBuilder builder = new StringBuilder();
		for(int token:script) {
	
			builder.append(TokenBuilder.tokenToString(token));
			
			builder.append("\n");
		}
		return builder.toString();
	}
	
//TODO: build FAST debugger that can break data without template on stop bit and provide multiple possible interpretations.
	
	// Runs very well with these JVM arguments
	// -XX:CompileThreshold=64 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+AggressiveOpts -XX:MaxInlineLevel=20
	// ?? -XX:+UseFPUForSpilling -XX:InlineSmallCode=65536
			
	@Test
	public void testDecodeComplex30000() {	
		
		FASTInput templateCatalogInput = new FASTInputByteArray(buildRawCatalogData());
		TemplateCatalog catalog = new TemplateCatalog(new PrimitiveReader(templateCatalogInput));
		
		byte prefixSize = 4;
		catalog.setMessagePrefix(prefixSize);	
		
		int maxByteVector = 0;
		catalog.setMaxByteVectorLength(maxByteVector);
				
		int maxTextLength = 14;
		catalog.setMaxTextLength(maxTextLength);
		
		//connect to file		
		URL sourceData = getClass().getResource("/performance/complex30000.dat");

		FASTInputByteArray fastInput = buildInputForTesting(new File(sourceData.getFile()));
		int totalTestBytes = fastInput.remaining();
		PrimitiveReader primitiveReader = new PrimitiveReader(fastInput);
		FASTDynamicReader dynamicReader = new FASTDynamicReader(primitiveReader, catalog);
		
		System.gc();
		
		int warmup = 30;//set much larger for profiler
		int count = 5;
		int result = 0;
		
		FASTRingBuffer queue = dynamicReader.ringBuffer();
		
		int msgs = 0;
		int grps = 0;
		int iter = warmup;
		while (--iter>=0) {
			msgs = 0;
			grps = 0;
			int flag = 0; //same id needed for writer construction
			while (0!=(flag = dynamicReader.hasMore())) {
				//New flags TODO: build some constants.
				//0  eof
				//1  has sequence group to read (TODO: refactor to always be end of sequence?
				//2  end of message
				//neg  unable to write
				
				
				if (0!=(flag&0x02)) {
					msgs++;
					//this is a template message. TODO: need unit test here.
					int templateId = queue.readInteger(0);
					assertTrue(1==templateId || 2==templateId || 99==templateId);
					
					//must dump values in buffer or we will hang when reading.
					//only dump at end of template not end of sequence.
					//the removePosition must remain at the beginning until message is complete.					
					queue.dump(); 
				} else {
					
					
				}
				grps++;
				
				
			}		
			fastInput.reset();
			primitiveReader.reset();
			dynamicReader.reset();
		}
		
		iter = count;
		while (--iter>=0) {

			double start = System.nanoTime();
			int flag;
			while (0!=(flag=dynamicReader.hasMore())) {
				if (0!=(flag&0x02)) {
					result|=queue.readInteger(0);//must do some real work or hot-spot may delete this loop.
					queue.dump(); //must dump values in buffer or we will hang when reading.
				}
				
			}				
			double duration = System.nanoTime()-start;
			
			int ns = (int)(duration/count);
			float mmsgPerSec = (msgs*(float)1000l/ns);
			float nsPerByte = (ns/(float)totalTestBytes);
			int mbps = (int)((1000l*totalTestBytes*8l)/ns);
					
			System.err.println("Duration:"+ns+"ns "+
					           " "+mmsgPerSec+"MM/s "+
					           " "+nsPerByte+"nspB "+
					           " "+mbps+"mbps "+
					           " Bytes:"+totalTestBytes+
					           " Messages:"+msgs+
   			           		   " Groups:"+grps); //Phrases/Clauses
			
			////////
			//reset the data to run the test again.
			////////
			fastInput.reset();
			primitiveReader.reset();
			dynamicReader.reset();
			
		}
		assertTrue(result!=0);	
		
	}

	@Test
	public void testDecodeEncodeComplex30000() {	
		FASTInput templateCatalogInput = new FASTInputByteArray(buildRawCatalogData());
		TemplateCatalog catalog = new TemplateCatalog(new PrimitiveReader(templateCatalogInput));
		
		byte prefixSize = 4;
		catalog.setMessagePrefix(prefixSize);	
		
		//connect to file		
		URL sourceData = getClass().getResource("/performance/complex30000.dat");

		File fileSource = new File(sourceData.getFile());
		FASTInputByteArray fastInput = buildInputForTesting(fileSource);
		int totalTestBytes = fastInput.remaining();
		PrimitiveReader primitiveReader = new PrimitiveReader(fastInput);
		FASTDynamicReader dynamicReader = new FASTDynamicReader(primitiveReader, catalog);
		
		byte[] targetBuffer = new byte[(int)fileSource.length()];
		FASTOutputByteArray fastOutput = new FASTOutputByteArray(targetBuffer);
		PrimitiveWriter primitiveWriter = new PrimitiveWriter(fastOutput);
		FASTDynamicWriter dynamicWriter = new FASTDynamicWriter(primitiveWriter, catalog);
		
		System.gc();
		
		int warmup = 3;//set much larger for profiler
		int count = 5;
		
		FASTRingBuffer queue = dynamicReader.ringBuffer();
		
		int msgs = 0;
		int grps = 0;
		int iter = warmup;
		while (--iter>=0) {
			msgs = 0;
			grps = 0;
			int data = 0; //same id needed for writer construction
			while (0!=(data = dynamicReader.hasMore())) {
				dynamicWriter.write(queue);
				grps++;
				if (0==(data&0x3FF)) {
					msgs++;
				}
			}		
			fastInput.reset();
			primitiveReader.reset();
			dynamicReader.reset();
		}
		
		iter = count;
		while (--iter>=0) {

			double start = System.nanoTime();
				while (0!=dynamicReader.hasMore()) {
					dynamicWriter.write(queue);
				}
				
			double duration = System.nanoTime()-start;
			int ns = (int)(duration/count);
			float mmsgPerSec = (msgs*(float)1000l/ns);
			float nsPerByte = (ns/(float)totalTestBytes);
					
			System.err.println("Duration:"+ns+"ns "+
					           " "+mmsgPerSec+"MM/s "+
					           " "+nsPerByte+"ns/B "+
					           " Bytes:"+totalTestBytes+
					           " Messages:"+msgs+
					           " Groups:"+grps); 
			
			//TODO: confirm generated bytes match parsed.
			
			////////
			//reset the data to run the test again.
			////////
			fastInput.reset();
			primitiveReader.reset();
			dynamicReader.reset();
			
		}	
				
	}
	
	private FASTInputByteArray buildInputForTesting(File fileSource) {
		byte[] fileData = null;
		try {
			//do not want to time file access so copy file to memory
			fileData = new byte[(int) fileSource.length()];
			FileInputStream inputStream = new FileInputStream(fileSource);
			int readBytes = inputStream.read(fileData);
			inputStream.close();
			assertEquals(fileData.length,readBytes);
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}	
			
			FASTInputByteArray fastInput = new FASTInputByteArray(fileData);
		return fastInput;
	}

	
	private String hexString(byte[] targetBuffer) {
		StringBuilder builder = new StringBuilder();
		
		for(byte b:targetBuffer) {
			
			String tmp = Integer.toHexString(0xFF&b);
			builder.append(tmp.substring(Math.max(0, tmp.length()-2))).append(" ");
			
		}
		return builder.toString();
	}
	
	private String binString(byte[] targetBuffer) {
		StringBuilder builder = new StringBuilder();
		
		for(byte b:targetBuffer) {
			
			String tmp = Integer.toBinaryString(0xFF&b);
			builder.append(tmp.substring(Math.max(0, tmp.length()-8))).append(" ");
			
		}
		return builder.toString();
	}

	private byte[] buildRawCatalogData() {
		URL source = getClass().getResource("/performance/example.xml");
			
		
		ByteArrayOutputStream catalogBuffer = new ByteArrayOutputStream(4096);
		File fileSource = new File(source.getFile());
		try {			
			TemplateLoader.buildCatalog(catalogBuffer, fileSource);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			e.printStackTrace();
		}
		
		assertTrue("Catalog must be built.",catalogBuffer.size()>0);
		
		byte[] catalogByteArray = catalogBuffer.toByteArray();
		return catalogByteArray;
	}
	
}
