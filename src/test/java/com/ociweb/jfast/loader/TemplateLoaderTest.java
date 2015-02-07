package com.ociweb.jfast.loader;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.Test;

import com.ociweb.jfast.FAST;
import com.ociweb.jfast.catalog.loader.ClientConfig;
import com.ociweb.jfast.catalog.loader.DictionaryFactory;
import com.ociweb.jfast.catalog.loader.TemplateCatalogConfig;
import com.ociweb.jfast.catalog.loader.TemplateLoader;
import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.generator.DispatchLoader;
import com.ociweb.jfast.generator.FASTClassLoader;
import com.ociweb.jfast.primitive.FASTInput;
import com.ociweb.jfast.primitive.FASTOutput;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTInputByteBuffer;
import com.ociweb.jfast.primitive.adapter.FASTInputStream;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArrayEquals;
import com.ociweb.jfast.primitive.adapter.FASTOutputTotals;
import com.ociweb.pronghorn.ring.RingBuffer;
import com.ociweb.pronghorn.ring.RingBuffers;
import com.ociweb.pronghorn.ring.RingWalker;
import com.ociweb.pronghorn.ring.RingReader;
import com.ociweb.pronghorn.ring.token.OperatorMask;
import com.ociweb.pronghorn.ring.token.TokenBuilder;
import com.ociweb.pronghorn.ring.token.TypeMask;
import com.ociweb.pronghorn.ring.util.Histogram;
import com.ociweb.jfast.stream.DispatchObserver;
import com.ociweb.jfast.stream.FASTDecoder;
import com.ociweb.jfast.stream.FASTDynamicWriter;
import com.ociweb.jfast.stream.FASTEncoder;
import com.ociweb.jfast.stream.FASTReaderReactor;
import com.ociweb.jfast.stream.FASTListener;
import com.ociweb.jfast.stream.FASTReaderInterpreterDispatch;
import com.ociweb.jfast.stream.FASTWriterInterpreterDispatch;
import com.ociweb.jfast.util.Profile;

public class TemplateLoaderTest {

    
    
    @Test
    public void buildRawCatalog() {

        byte[] catalogByteArray = buildRawCatalogData(new ClientConfig());
        assertEquals(762, catalogByteArray.length);
               
        
        // reconstruct Catalog object from stream
        TemplateCatalogConfig catalog = new TemplateCatalogConfig(catalogByteArray);

        boolean ok = false;
        int[] script = null;
        try {
            // /performance/example.xml contains 3 templates.
            assertEquals(3, catalog.templatesCount());

            script = catalog.fullScript();
            assertEquals(54, script.length);
            assertEquals(TypeMask.Group, TokenBuilder.extractType(script[0]));// First
                                                                                  // Token

            // CMD:Group:010000/Close:PMap::010001/9
            assertEquals(TypeMask.Group, TokenBuilder.extractType(script[script.length - 1]));// Last
                                                                                              // Token

            ok = true;
        } finally {
            if (!ok) {
                System.err.println("Script Details:");
                if (null != script) {
                    System.err.println(convertScriptToString(script));
                }
            }
        }
    }

    private String convertScriptToString(int[] script) {
        StringBuilder builder = new StringBuilder();
        for (int token : script) {

            builder.append(TokenBuilder.tokenToString(token));

            builder.append("\n");
        }
        return builder.toString();
    }

//TODO: B, double check that template script generated by the SAX parser uses script positions vs tempalte ids? Which is right?
    
    @Test
    public void testDecodeComplex30000() {
        
      Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        
        byte[] catBytes = buildRawCatalogData(new ClientConfig());
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes); 
        
        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));
        long totalTestBytes = sourceDataFile.length();

        PrimitiveReader reader = new PrimitiveReader(buildInputArrayForTesting(sourceDataFile), TemplateCatalogConfig.maxPMapCountInBytes(catalog));
        
        FASTClassLoader.deleteFiles();
        
        FASTDecoder readerDispatch = DispatchLoader.loadDispatchReader(catBytes, RingBuffers.buildNoFanRingBuffers(new RingBuffer((byte)catalog.clientConfig().getPrimaryRingBits(),(byte)catalog.clientConfig().getTextRingBits(),catalog.ringByteConstants(), catalog.getFROM()))); 
    //    FASTDecoder readerDispatch = new FASTReaderInterpreterDispatch(catBytes);//not using compiled code
        

        Histogram stats = new Histogram(100000,13000000,1000000,100000000);    
        
        
        System.err.println("using: "+readerDispatch.getClass().getSimpleName());
        System.gc();
        
        RingBuffer queue = RingBuffers.get(readerDispatch.ringBuffers,0);      

        int warmup = 128;
        int count = 512;
        final int[] fullScript = catalog.getScriptTokens();
        
        
        final byte[] preamble = new byte[catalog.clientConfig.getPreableBytes()];

        final AtomicInteger msgs = new AtomicInteger();
        int frags = 0;      
        
        final AtomicLong totalBytesOut = new AtomicLong();
        final AtomicLong totalRingInts = new AtomicLong();

        
        FASTReaderReactor reactor=null;

        
        int iter = warmup;
        while (--iter >= 0) {
            msgs.set(0);
            frags = 0;

            reactor = new FASTReaderReactor(readerDispatch,reader);
            RingBuffer rb = reactor.ringBuffers()[0];
            rb.reset();

            while (FASTReaderReactor.pump(reactor)>=0) { //continue if there is no room or if a fragment is read.
                if (RingWalker.tryReadFragment(rb)) {
	                	
	                frags++;
	                if (RingWalker.isNewMessage(rb.consumerData)) {
	                    final int msgIdx = RingWalker.getMsgIdx(rb.consumerData);
	                    
	                    msgs.incrementAndGet();
	                    
	
	                    // this is a template message.
	                    int bufferIdx = 0;
	                    
	                    if (preamble.length > 0) {
	                        int i = 0;
	                        int s = preamble.length;
	                        while (i < s) {
	                         	RingBuffer.readInt(queue.buffer, queue.mask, queue.workingTailPos.value+bufferIdx);
	                            i += 4;
	                            bufferIdx++;
	                        }
	                    }
	
	                   // int templateId2 = FASTRingBufferReader.readInt(queue, bufferIdx);
	                    bufferIdx += 1;// point to first field
	                    assertTrue("found " + msgIdx, 36 == msgIdx || 3 == msgIdx || 0 == msgIdx);
	
	                    int i = msgIdx;
	                    // System.err.println("new templateId "+templateId);
	                    while (true) {
	                        int token = fullScript[i++];
	                        // System.err.println("xxx:"+bufferIdx+" "+TokenBuilder.tokenToString(token));
	
	                        if (isText(token)) {
	                            
	                        	
	                        //	assert((bufferIdx&0x1E<<RingReader.OFF_BITS)==0x8<<RingReader.OFF_BITS || (bufferIdx&0x1E<<RingReader.OFF_BITS)==0x5<<RingReader.OFF_BITS || (bufferIdx&0x1E<<RingReader.OFF_BITS)==0xE<<RingReader.OFF_BITS) : "Expected to read some type of ASCII/UTF8/BYTE but found "+TypeMask.toString((bufferIdx>>RingReader.OFF_BITS)&TokenBuilder.MASK_TYPE);
							//	int readDataLength = queue.buffer[queue.mask & (int)(queue.consumerData.activeReadFragmentStack[RingReader.STACK_OFF_MASK&(bufferIdx>>RingReader.STACK_OFF_SHIFT)] + (RingReader.OFF_MASK&bufferIdx) + 1)];
								
								int readDataLength = RingBuffer.readInt(queue.buffer, queue.mask, queue.workingTailPos.value+bufferIdx+1);
								totalBytesOut.addAndGet(4 * readDataLength);
	                        }
	
	                        // find the next index after this token.
	                        int fSize = TypeMask.ringBufferFieldSize[TokenBuilder.extractType(token)];
	                        bufferIdx += fSize;
	
	                        if (i==fullScript.length) {
	                        	break;
	                        }
	                        if (TypeMask.GroupLength == TokenBuilder.extractType(token)) {
	                        	break;
	                        }
	                        if (TypeMask.Group == TokenBuilder.extractType(token) && 
	                        	0!=(OperatorMask.Group_Bit_Close & TokenBuilder.extractOper(token))	) {
	                        	break;
	                        }
	                        
	                    }
	                    totalBytesOut.addAndGet(4 * bufferIdx);
	                    totalRingInts.addAndGet(bufferIdx);
	
	                    // must dump values in buffer or we will hang when reading.
	                    // only dump at end of template not end of sequence.
	                    // the removePosition must remain at the beginning until
	                    // message is complete.
	                    
	                    //NOTE: MUST NOT DUMP IN THE MIDDLE OF THIS LOOP OR THE PROCESSING GETS OFF TRACK
	                    //FASTRingBuffer.dump(queue);
	                //    rb.tailPos.lazySet(rb.workingTailPos.value);
	                }
                } else {
                	fail("No data?");
                }
            }
            
            rb.reset();
            
            
            //fastInput.reset();
            PrimitiveReader.reset(reader);
            readerDispatch.sequenceCountStackHead = -1;            
            RingBuffers.reset(readerDispatch.ringBuffers);
                        
  //          System.err.println(reactor.stats.toString()+" ns");
            
        }
        if (warmup>0) {
            totalBytesOut.set(totalBytesOut.longValue()/warmup);
            totalRingInts.set(totalRingInts.longValue()/warmup);
        }
        
        Profile.start();
        
        iter = count+warmup;
        while (--iter >= 0) {
            if (Thread.interrupted()) {
                System.exit(0);
            }
            
            reactor = new FASTReaderReactor(readerDispatch,reader);
            
            RingBuffer rb = null; 
            rb =  RingBuffers.get(readerDispatch.ringBuffers,0);
            rb.reset();
            double duration = 0;
            
            try{
                double start = System.nanoTime();
    
                //Preload the ringBuffer with a few pumps to ensure we
                //are not testing against an always empty buffer.
                int few = 4;
                while (--few>=0) {
                    FASTReaderReactor.pump(reactor);
                }               
                while (FASTReaderReactor.pump(reactor)>=0) { //72-88
                 //   FASTRingBuffer.dump(rb);
                    //int tmp = Profile.version.get();
                    if (RingWalker.tryReadFragment(rb)) {
                       // rb.tailPos.lazySet(rb.workingTailPos.value);
                    }; //11
                    //Profile.count += (Profile.version.get()-tmp);
                }
                //the buffer has extra records in it so we must clean them out here.
                while (RingWalker.tryReadFragment(rb)) {
                     
                   // rb.tailPos.lazySet(rb.workingTailPos.value);
                }
                
                duration = System.nanoTime() - start;
            } catch (Throwable ie) {
               ie.printStackTrace();
               System.exit(0);
            }
            if (iter<count) {
                Histogram.sample((long)duration, stats);
                
                if ((0x7F & iter) == 0) {
                    int ns = (int) stats.valueAtPercent(.60);//duration;
                    float mmsgPerSec = (msgs.intValue() * (float) 1000l / ns);
                    float nsPerByte = (ns / (float) totalTestBytes);
                    int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);
                    
                    float mfieldPerSec = (totalRingInts.longValue()* (float) 1000l / ns);
    
                    System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                            + " " + mbps + "mbps " + " In:" + totalTestBytes + " Out:" + totalBytesOut + " cmpr:"
                            + (1f-(totalTestBytes / (float) totalBytesOut.longValue())) + " Messages:" + msgs + " Frags:" + frags
                            + " RingInts:"+totalRingInts+ " mfps "+mfieldPerSec 
                            ); // Phrases/Clauses
                    // Helps let us kill off the job.
                }
            }

            // //////
            // reset the data to run the test again.
            // //////
            //fastInput.reset();
            PrimitiveReader.reset(reader);
            FASTDecoder.reset(catalog.dictionaryFactory(), readerDispatch);
            
   //         rb.tailPos.lazySet(rb.workingTailPos.value);

        }
        System.err.println(stats.toString()+" ns  total:"+Histogram.sampleCount(stats));
        
        System.err.println(Profile.results());

        
    }
    
    
    @Test
    public void testDecodeComplex30000Minimal() {
        
        
        byte[] catBytes = buildRawCatalogData(new ClientConfig());
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes); 

        System.err.println("cat bytes:"+catBytes.length);
        
        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));

        System.err.println(sourceDataFile.getName()+ " "+sourceDataFile.length());
        
        FASTInput fastInput = null;
		try {
			FileInputStream fist = new FileInputStream(sourceDataFile);
			fastInput = new FASTInputStream(fist);
		} catch (FileNotFoundException e) {
			
			throw new RuntimeException(e);
		}

        FASTClassLoader.deleteFiles();
        final AtomicInteger msgs = new AtomicInteger();

        FASTReaderReactor reactor = FAST.inputReactor(fastInput, catBytes, RingBuffers.buildNoFanRingBuffers(new RingBuffer((byte)catalog.clientConfig().getPrimaryRingBits(),(byte)catalog.clientConfig().getTextRingBits(),catalog.ringByteConstants(), catalog.getFROM()))); 
        
        assertEquals(1,reactor.ringBuffers().length);
        RingBuffer rb = reactor.ringBuffers()[0];
        rb.reset();

        while (FASTReaderReactor.pump(reactor)>=0) { //continue if there is no room or if a fragment is read.
            if (RingWalker.tryReadFragment(rb)) {	
	            if (RingWalker.isNewMessage(rb.consumerData)) {
	                int templateId = RingWalker.getMsgIdx(rb.consumerData);
	                msgs.incrementAndGet();
	            }
            }
        }
        System.out.println("total messages:"+msgs);
       
        
    }

    private boolean isText(int token) {
        return 0x08 == (0x1F & (token >>> TokenBuilder.SHIFT_TYPE));
    }

    private FASTInputByteBuffer buildInputForTestingByteBuffer(File sourceDataFile) {
        long totalTestBytes = sourceDataFile.length();
        FASTInputByteBuffer fastInput = null;
        try {
            FileChannel fc = new RandomAccessFile(sourceDataFile, "r").getChannel();
            MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalTestBytes);
            fastInput = new FASTInputByteBuffer(mem);
            fc.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fastInput;
    }



    @Test
    public void testDecodeEncodeComplex30000() {
        
        FASTClassLoader.deleteFiles();
        
        byte[] catBytes = buildRawCatalogData(new ClientConfig());
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes);
        int maxPMapCountInBytes = TemplateCatalogConfig.maxPMapCountInBytes(catalog);   

        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));
        long totalTestBytes = sourceDataFile.length();
        final byte[] testBytesData = buildInputArrayForTesting(sourceDataFile);

        FASTInputByteArray fastInput = new FASTInputByteArray(testBytesData);

        // New memory mapped solution. No need to cache because we warm up and
        // OS already has it.
        // FASTInputByteBuffer fastInput =
        // buildInputForTestingByteBuffer(sourceDataFile);

        PrimitiveReader reader = new PrimitiveReader(2048, fastInput, maxPMapCountInBytes);
        
        FASTDecoder readerDispatch = DispatchLoader.loadDispatchReaderDebug(catBytes, RingBuffers.buildNoFanRingBuffers(new RingBuffer((byte)catalog.clientConfig().getPrimaryRingBits(),(byte)catalog.clientConfig().getTextRingBits(),catalog.ringByteConstants(), catalog.getFROM()))); 
        
       // readerDispatch = new FASTReaderInterpreterDispatch(catBytes);//not using compiled code
      
       System.err.println("using: "+readerDispatch.getClass().getSimpleName());
        
        final AtomicInteger msgs = new AtomicInteger();
        
        FASTReaderReactor reactor = new FASTReaderReactor(readerDispatch,reader);
        
        RingBuffer queue = RingBuffers.get(readerDispatch.ringBuffers,0);

        FASTOutputByteArrayEquals fastOutput = new FASTOutputByteArrayEquals(testBytesData,RingBuffer.from(queue).tokens);

        int writeBuffer = 256;        
        PrimitiveWriter writer = new PrimitiveWriter(writeBuffer, fastOutput, false);
        
        //unusual case just for checking performance. Normally one could not pass the catalog.ringBuffer() in like this.        
        //FASTEncoder writerDispatch = new FASTWriterInterpreterDispatch(catalog, readerDispatch.ringBuffers);
        FASTEncoder writerDispatch = DispatchLoader.loadDispatchWriterDebug(catBytes);
        //System.err.println("using: "+writerDispatch.getClass().getSimpleName());

        FASTDynamicWriter dynamicWriter = new FASTDynamicWriter(writer, queue, writerDispatch);

        System.gc();
        
        int warmup = 20;// set much larger for profiler
        int count = 128;
        

        long wroteSize = 0;
        msgs.set(0);
        int grps = 0;
        int iter = warmup;
        while (--iter >= 0) {
            msgs.set(0);
            grps = 0;
            DictionaryFactory dictionaryFactory = writerDispatch.dictionaryFactory;
            
            dictionaryFactory.reset(writerDispatch.rIntDictionary);
            dictionaryFactory.reset(writerDispatch.rLongDictionary);
            dictionaryFactory.reset(writerDispatch.byteHeap);
            
            while (FASTReaderReactor.pump(reactor)>=0) { //continue if there is no room or a fragment is read

                    if (RingWalker.tryReadFragment(queue)) {
                        if (RingWalker.isNewMessage(queue.consumerData)) {
                            msgs.incrementAndGet();
                            //this is very similar to low level api so we must do this
                           // queue.bytesHeadPos.lazySet(queue.byteWorkingHeadPos.value);    	
                        }
                        try{   
                            FASTDynamicWriter.write(dynamicWriter);
                        } catch (FASTException e) {
                            System.err.println("ERROR: cursor at "+writerDispatch.getActiveScriptCursor()+" "+TokenBuilder.tokenToString(RingBuffer.from(queue).tokens[writerDispatch.getActiveScriptCursor()]));
                            throw e;
                        }    
                       
                        grps++;
                    }
            }            

            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            FASTDecoder.reset(catalog.dictionaryFactory(), readerDispatch);

            PrimitiveWriter.flush(writer);
            wroteSize = Math.max(wroteSize, PrimitiveWriter.totalWritten(writer));
            fastOutput.reset();
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

        }

        // Expected total read fields:2126101
        assertEquals("test file bytes", totalTestBytes, wroteSize);

        iter = count;
        while (--iter >= 0) {

            DictionaryFactory dictionaryFactory = writerDispatch.dictionaryFactory;
            dictionaryFactory.reset(writerDispatch.rIntDictionary);
            dictionaryFactory.reset(writerDispatch.rLongDictionary);
            dictionaryFactory.reset(writerDispatch.byteHeap);
            double start = System.nanoTime();
            
            while (FASTReaderReactor.pump(reactor)>=0) {  
                    if (RingWalker.tryReadFragment(queue)) {
                       if (RingWalker.getMsgIdx(queue.consumerData)>=0) { //skip if we are waiting for more content.
                                FASTDynamicWriter.write(dynamicWriter);  
                             //   RingBuffer.releaseReadLock(queue);
                       }
                    }
            }
            
            
            double duration = System.nanoTime() - start;

            if ((0x3F & iter) == 0) {
                int ns = (int) duration;
                float mmsgPerSec = (msgs.intValue() * (float) 1000l / ns);
                float nsPerByte = (ns / (float) totalTestBytes);
                int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);

                System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                        + " " + mbps + "mbps " + " Bytes:" + totalTestBytes + " Messages:" + msgs + " Groups:" + grps); // Phrases/Clauses
            }

            // //////
            // reset the data to run the test again.
            // //////
            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            FASTDecoder.reset(catalog.dictionaryFactory(), readerDispatch);

            fastOutput.reset();
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

        }

    }

    
    @Test
    public void testEncodeComplex30000() {
        
        FASTClassLoader.deleteFiles();
        
        byte[] catBytes = buildRawCatalogData(new ClientConfig(22,20));
        final TemplateCatalogConfig catalog = new TemplateCatalogConfig(catBytes);
        int maxPMapCountInBytes = TemplateCatalogConfig.maxPMapCountInBytes(catalog);   

        // connect to file
        URL sourceData = getClass().getResource("/performance/complex30000.dat");
        File sourceDataFile = new File(sourceData.getFile().replace("%20", " "));
        long totalTestBytes = sourceDataFile.length();
        final byte[] testBytesData = buildInputArrayForTesting(sourceDataFile);

        FASTInputByteArray fastInput = new FASTInputByteArray(testBytesData);


        final AtomicInteger msgs = new AtomicInteger();

        PrimitiveReader reader = new PrimitiveReader(4096, fastInput, maxPMapCountInBytes);     
        
        FASTDecoder readerDispatch = DispatchLoader.loadDispatchReader(catBytes, RingBuffers.buildNoFanRingBuffers(new RingBuffer((byte)catalog.clientConfig().getPrimaryRingBits(),(byte)catalog.clientConfig().getTextRingBits(),catalog.ringByteConstants(), catalog.getFROM())));   
        FASTReaderReactor reactor = new FASTReaderReactor(readerDispatch,reader);
        
        
        RingBuffer queue = RingBuffers.get(readerDispatch.ringBuffers,0);

        FASTOutputByteArrayEquals fastOutput = new FASTOutputByteArrayEquals(testBytesData,RingBuffer.from(queue).tokens);
        
               
        int writeBuffer = 16384;
        boolean minimizeLatency = false;
        PrimitiveWriter writer = new PrimitiveWriter(writeBuffer, fastOutput, minimizeLatency);
        
        //unusual case just for checking performance. Normally one could not pass the catalog.ringBuffer() in like this.        
         //FASTEncoder writerDispatch = new FASTWriterInterpreterDispatch(catalog, readerDispatch.ringBuffers);
         FASTEncoder writerDispatch = DispatchLoader.loadDispatchWriter(catBytes);

        System.err.println("using: "+writerDispatch.getClass().getSimpleName());

        FASTDynamicWriter dynamicWriter = new FASTDynamicWriter(writer, queue, writerDispatch);

        System.gc();
        
        int warmup = 20;// set much larger for profiler
        int count = 512;
        

        long wroteSize = 0;
        msgs.set(0);
        int grps = 0;
        int iter = warmup;
        while (--iter >= 0) {
            msgs.set(0);
            grps = 0;
            DictionaryFactory dictionaryFactory = writerDispatch.dictionaryFactory;
            
            dictionaryFactory.reset(writerDispatch.rIntDictionary);
            dictionaryFactory.reset(writerDispatch.rLongDictionary);
            dictionaryFactory.reset(writerDispatch.byteHeap);
            
            //read from reader and puts messages on the queue
            while (FASTReaderReactor.pump(reactor)>=0) { //continue if there is no room or a fragment is read

            		//confirms full message to read on the queue            	
                    if (RingWalker.tryReadFragment(queue)) {
                        if (RingWalker.isNewMessage(queue.consumerData)) {
                            msgs.incrementAndGet();
                        }
                        try{   
                        	//write message found on the queue to the output writer
                            FASTDynamicWriter.write(dynamicWriter);
                        } catch (FASTException e) {
                            System.err.println("ERROR: cursor at "+writerDispatch.getActiveScriptCursor()+" "+TokenBuilder.tokenToString(RingBuffer.from(queue).tokens[writerDispatch.getActiveScriptCursor()]));
                            throw e;
                        }                            
                        grps++;
                    }

            }
            

            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            FASTDecoder.reset(catalog.dictionaryFactory(), readerDispatch);

            PrimitiveWriter.flush(writer);
            wroteSize = Math.max(wroteSize, PrimitiveWriter.totalWritten(writer));
            fastOutput.reset();
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

        }

        // Expected total read fields:2126101
        assertEquals("test file bytes", totalTestBytes, wroteSize);

        //In the warm up we checked the writes for accuracy, here we are only going for speed
        //so the FASTOutput instance is changed to one that only writes.
        FASTOutputByteArray fastOutput2 = new FASTOutputByteArray(testBytesData);
 //       FASTOutput fastOutput2 = new FASTOutputTotals();
        
        writer = new PrimitiveWriter(writeBuffer, fastOutput2, minimizeLatency);
        dynamicWriter = new FASTDynamicWriter(writer, queue, writerDispatch);
        
        boolean concurrent = false; //when set true this is not realistic use case but it is a nice test point.
                
        iter = count;
        while (--iter >= 0) {

            DictionaryFactory dictionaryFactory = writerDispatch.dictionaryFactory;
            dictionaryFactory.reset(writerDispatch.rIntDictionary);
            dictionaryFactory.reset(writerDispatch.rLongDictionary);
            dictionaryFactory.reset(writerDispatch.byteHeap);
            
            final ThreadPoolExecutor executor = (ThreadPoolExecutor)Executors.newFixedThreadPool(1); 

            //note this test is never something that represents a normal use case but it is good for testing the encoding only time.
            //      
            //TODO: X, allow decoding in parallel by n cores into n ring buffers but let each one use different techniques.  The first one done is the value used. Would support runtime optimizations.
            
            
          
            
            //Pre-populate the ring buffer to only measure the write time.
            final AtomicBoolean isAlive = reactor.start(executor, reader);
            double start;
            
            if (concurrent) {
                start = System.nanoTime(); 
                while (isAlive.get()) {
                    while (RingWalker.tryReadFragment(queue)) {
                        FASTDynamicWriter.write(dynamicWriter);  
                    }   
                }
                while (RingWalker.tryReadFragment(queue)) {
                    FASTDynamicWriter.write(dynamicWriter);  
                }
                
            } else {
                //wait until everything is decoded  
            
                while (isAlive.get()) {                
                }
                //now start the timer
                start = System.nanoTime();            
                
                while (RingWalker.tryReadFragment(queue)) {
                        FASTDynamicWriter.write(dynamicWriter);  
                } 
            }
            double duration = System.nanoTime() - start;
            // Only shut down after is alive is finished.
            executor.shutdown();
            
            try {
                executor.awaitTermination(1,TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            
            }
            

            if ((0x3F & iter) == 0) {
                int ns = (int) duration;
                float mmsgPerSec = (msgs.intValue() * (float) 1000l / ns);
                float nsPerByte = (ns / (float) totalTestBytes);
                int mbps = (int) ((1000l * totalTestBytes * 8l) / ns);

                System.err.println("Duration:" + ns + "ns " + " " + mmsgPerSec + "MM/s " + " " + nsPerByte + "nspB "
                        + " " + mbps + "mbps " + " Bytes:" + totalTestBytes + " Messages:" + msgs + " Groups:" + grps +" "+(concurrent?"concurrent":"sequential")); // Phrases/Clauses
            }

            // //////
            // reset the data to run the test again.
            // //////
            queue.reset();

            fastInput.reset();
            PrimitiveReader.reset(reader);
            FASTDecoder.reset(catalog.dictionaryFactory(), readerDispatch);

            fastOutput2.reset();
            
            PrimitiveWriter.reset(writer);
            dynamicWriter.reset(true);

        }

    }

	static byte[] buildInputArrayForTesting(File fileSource) {
        byte[] fileData = null;
        try {
            // do not want to time file access so copy file to memory
            fileData = new byte[(int) fileSource.length()];
            FileInputStream inputStream = new FileInputStream(fileSource);
            int readBytes = inputStream.read(fileData);
            inputStream.close();
            assertEquals(fileData.length, readBytes);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileData;
    }
    
  

    public static byte[] buildRawCatalogData(ClientConfig clientConfig) {
        //this example uses the preamble feature
        clientConfig.setPreableBytes((short)4);

        ByteArrayOutputStream catalogBuffer = new ByteArrayOutputStream(4096);
        try {
            TemplateLoader.buildCatalog(catalogBuffer, "/performance/example.xml", clientConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assertTrue("Catalog must be built.", catalogBuffer.size() > 0);

        byte[] catalogByteArray = catalogBuffer.toByteArray();
        return catalogByteArray;
    }


}
