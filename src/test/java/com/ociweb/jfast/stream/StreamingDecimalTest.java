//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import static org.junit.Assert.assertEquals;

import org.junit.AfterClass;
import org.junit.Test;

import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.ClientConfig;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.TemplateCatalogConfig;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArray;

public class StreamingDecimalTest extends BaseStreamingTest {

    final long[] testData = buildTestDataUnsignedLong(fields);
    final int testExpConst = 0;
    final long testMantConst = 0;

    // Must double because we may need 1 bit for exponent and another for
    // mantissa
    final int pmapSize = maxMPapBytes * 2;
    final int groupToken = TokenBuilder.buildToken(TypeMask.Group, maxMPapBytes > 0 ? OperatorMask.Group_Bit_PMap : 0,
            pmapSize, TokenBuilder.MASK_ABSENT_DEFAULT);

    boolean sendNulls = true;

    FASTOutputByteArray output;
    PrimitiveWriter writer;

    FASTInputByteArray input;
    PrimitiveReader reader;
    

    FASTReaderInterpreterDispatch fr;

    // NO PMAP
    // NONE, DELTA, and CONSTANT(non-optional)

    // Constant can never be optional but can have pmap.

    @AfterClass
    public static void cleanup() {
        System.gc();
    }

    @Test
    public void decimalTest() {
        System.gc();

        int[] types = new int[] { TypeMask.Decimal, TypeMask.DecimalOptional, };

        int[] operators = new int[] {
                OperatorMask.Field_None, // no need for pmap
                OperatorMask.Field_Delta, // no need for pmap
                OperatorMask.Field_Copy, OperatorMask.Field_Increment, OperatorMask.Field_Constant,
                OperatorMask.Field_Default };

        int i = 1;// set to large value for profiling
        while (--i >= 0) {

            tester(types, operators, "Decimal", 0);
        }

    }
    FASTRingBuffer rbRingBufferLocal = new FASTRingBuffer((byte)2,(byte)2,null, null);

    @Override
    protected long timeWriteLoop(int fields, int fieldsPerGroup, int maxMPapBytes, int operationIters,
            int[] tokenLookup, DictionaryFactory dcr) {

        FASTWriterInterpreterDispatch fw = new FASTWriterInterpreterDispatch(new TemplateCatalogConfig(dcr, 3, new int[0][0], null,
        64,4, 100,  new ClientConfig(8 ,7) ));

        long start = System.nanoTime();
        if (operationIters < 3) {
            throw new UnsupportedOperationException("must allow operations to have 3 data points but only had "
                    + operationIters);
        }

        int i = operationIters;
        int g = fieldsPerGroup;
        fw.openGroup(groupToken, pmapSize, writer);

        while (--i >= 0) {
            int f = fields;

            while (--f >= 0) {

                int token = tokenLookup[f];

                if (TokenBuilder.isOpperator(token, OperatorMask.Field_Constant)) {
                    long testValue;
                    if (sendNulls && ((i & 0xF) == 0) && TokenBuilder.isOptional(token)) {
                        testValue=TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG;
                        BaseStreamingTest.write(token, writer, fw);
                    } else {
                        testValue = testMantConst;
                        
                        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE)));
                        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));
                        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

                        //bridge solution as the ring buffer is introduce into all the APIs
                        FASTRingBuffer.dump(rbRingBufferLocal);
                       
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,testExpConst);
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,(int) (testValue >>> 32));
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,(int) (testValue & 0xFFFFFFFF)); 
                        FASTRingBuffer.unBlockFragment(rbRingBufferLocal.headPos,rbRingBufferLocal.workingHeadPos);
                        int rbPos = 0;

                        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                            fw.acceptIntegerSigned(token, rbPos, rbRingBufferLocal, writer);
                            fw.acceptLongSigned(token, rbPos+1, rbRingBufferLocal, writer);
                        } else {
                                    
                            int valueOfNull = TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT;
                            
                            fw.acceptIntegerSignedOptional(token, valueOfNull, rbPos, rbRingBufferLocal, writer);
                            fw.acceptLongSignedOptional(token, TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG, rbPos+1, rbRingBufferLocal, writer);
                        }
                    }
                } else {
                    if (sendNulls && ((f & 0xF) == 0) && TokenBuilder.isOptional(token)) {
                        BaseStreamingTest.write(token, writer, fw);
                    } else {
                        long mantissa = testData[f];
                        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE)));
                        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));
                        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

                        //bridge solution as the ring buffer is introduce into all the APIs
                        FASTRingBuffer.dump(rbRingBufferLocal);
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,1);
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,(int) (mantissa >>> 32));
                        FASTRingBuffer.addValue(rbRingBufferLocal.buffer,rbRingBufferLocal.mask,rbRingBufferLocal.workingHeadPos,(int) (mantissa & 0xFFFFFFFF)); 
                        FASTRingBuffer.unBlockFragment(rbRingBufferLocal.headPos,rbRingBufferLocal.workingHeadPos);
                        int rbPos = 0;

                        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {                                
                                
                            fw.acceptIntegerSigned(token, rbPos, rbRingBufferLocal, writer);                            
                            fw.acceptLongSigned(token, rbPos+1, rbRingBufferLocal, writer);
                        } else {
                                    
                            int valueOfNull = TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT;
                            
                            fw.acceptIntegerSignedOptional(token, valueOfNull, rbPos, rbRingBufferLocal, writer);

                            fw.acceptLongSignedOptional(token, TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG, rbPos+1, rbRingBufferLocal, writer);
                          
                        }
                    }
                }
                g = groupManagementWrite(fieldsPerGroup, fw, i, g, groupToken, groupToken, f, pmapSize, writer);
            }
        }
        if (((fieldsPerGroup * fields) % fieldsPerGroup) == 0) {
            fw.closeGroup(groupToken | (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER), writer);
        }
        fw.flush(writer);
        fw.flush(writer);

        return System.nanoTime() - start;
    }


    @Override
    protected long timeReadLoop(int fields, int fieldsPerGroup, int maxMPapBytes, int operationIters,
            int[] tokenLookup, DictionaryFactory dcr) {

        TemplateCatalogConfig testCatalog = new TemplateCatalogConfig(dcr, 3, new int[0][0], null, 64, maxGroupCount * 10, -1, new ClientConfig(8 ,7));
        fr = new FASTReaderInterpreterDispatch(testCatalog);
        

        long start = System.nanoTime();
        if (operationIters < 3) {
            throw new UnsupportedOperationException("must allow operations to have 3 data points but only had "
                    + operationIters);
        }

        long none = TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG;

        int i = operationIters;
        int g = fieldsPerGroup;

        fr.openGroup(groupToken, pmapSize, reader);

        while (--i >= 0) {
            int f = fields;

            while (--f >= 0) {

                int token = tokenLookup[f];

                if (TokenBuilder.isOpperator(token, OperatorMask.Field_Constant)) {
                    readDecimalConstant(tokenLookup, fr, none, f, token, i);

                } else {
                    readDecimalOthers(tokenLookup, fr, none, f, token);
                }
                g = groupManagementRead(fieldsPerGroup, fr, i, g, groupToken, f, pmapSize, reader);
            }
        }

        if (((fieldsPerGroup * fields) % fieldsPerGroup) == 0) {
            int idx = TokenBuilder.MAX_INSTANCE & groupToken;
            fr.closeGroup(groupToken | (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER),idx, reader);
        }

        long duration = System.nanoTime() - start;
        return duration;
    }

    private void readDecimalOthers(int[] tokenLookup, FASTReaderInterpreterDispatch fr, long none, int f, int token) {
        
        if (sendNulls && (f & 0xF) == 0 && TokenBuilder.isOptional(token)) {
            int exp = StreamingDecimalTest.readDecimalExponent(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (exp != TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT) {
                assertEquals(TokenBuilder.tokenToString(tokenLookup[f]),
                        TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT, exp);
            }
            long man = StreamingDecimalTest.readDecimalMantissa(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (none != man) {
                assertEquals(TokenBuilder.tokenToString(tokenLookup[f]), none, man);
            }
        } else {
            int exp = StreamingDecimalTest.readDecimalExponent(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            long man = StreamingDecimalTest.readDecimalMantissa(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (testData[f] != man) {
                assertEquals(testData[f], man);
            }
        }
    }

    private void readDecimalConstant(int[] tokenLookup, FASTReaderInterpreterDispatch fr, long none, int f, int token, int i) {
        if (sendNulls && (i & 0xF) == 0 && TokenBuilder.isOptional(token)) {
            int exp = StreamingDecimalTest.readDecimalExponent(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (exp != TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT) {
                assertEquals(TokenBuilder.tokenToString(tokenLookup[f]),
                        TemplateCatalogConfig.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT, exp);
            }
            long man = StreamingDecimalTest.readDecimalMantissa(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (none != man) {
                assertEquals(TokenBuilder.tokenToString(tokenLookup[f]), none, man);
            }
        } else {
            
            
            int exp = StreamingDecimalTest.readDecimalExponent(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            long man = StreamingDecimalTest.readDecimalMantissa(tokenLookup[f], reader, fr, RingBuffers.get(fr.ringBuffers,0));
            if (testMantConst != man) {
                assertEquals(testMantConst, man);
            }
            if (testExpConst != exp) {
                assertEquals(testExpConst, exp);
            }
        }
    }

    public long totalWritten() {
        return PrimitiveWriter.totalWritten(writer);
    }

    protected void resetOutputWriter() {
        output.reset();
        PrimitiveWriter.reset(writer);
    }

    protected void buildOutputWriter(int maxGroupCount, byte[] writeBuffer) {
        output = new FASTOutputByteArray(writeBuffer);
        writer = new PrimitiveWriter(4096, output, maxGroupCount, false);
    }

    protected long totalRead() {
        return PrimitiveReader.totalRead(reader);
    }

    protected void resetInputReader() {
        input.reset();
        PrimitiveReader.reset(reader);
    }

    protected void buildInputReader(int maxGroupCount, byte[] writtenData, int writtenBytes) {
        input = new FASTInputByteArray(writtenData, writtenBytes);
        reader = new PrimitiveReader(4096, input, maxGroupCount * 10);
    }

    public static long readDecimalMantissa(int token, PrimitiveReader reader, FASTReaderInterpreterDispatch decoder, FASTRingBuffer ringBuffer) {
        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        
        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
            // not optional
            decoder.readLongSigned(token, decoder.rLongDictionary, decoder.MAX_LONG_INSTANCE_MASK, decoder.readFromIdx, reader, RingBuffers.get(decoder.ringBuffers,0));
        } else {
            // optional
            decoder.readLongSignedOptional(token, decoder.rLongDictionary, decoder.MAX_LONG_INSTANCE_MASK, decoder.readFromIdx, reader, RingBuffers.get(decoder.ringBuffers,0));
        }
        
        //must return what was written
        return FASTRingBuffer.peekLong(ringBuffer.buffer, ringBuffer.workingHeadPos.value-2, ringBuffer.mask);
    }

    public static int readDecimalExponent(int token, PrimitiveReader reader, FASTReaderInterpreterDispatch decoder, FASTRingBuffer ringBuffer) {
        assert (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
                
        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
            // 00010 IntegerSigned
            decoder.readIntegerSigned(token, decoder.rIntDictionary, decoder.MAX_INT_INSTANCE_MASK, decoder.readFromIdx, reader, RingBuffers.get(decoder.ringBuffers,0));
        } else {
            // 00011 IntegerSignedOptional
            decoder.readIntegerSignedOptional(token, decoder.rIntDictionary, decoder.MAX_INT_INSTANCE_MASK, decoder.readFromIdx, reader, RingBuffers.get(decoder.ringBuffers,0));
        }
        //NOTE: for testing we need to check what was written
        return FASTRingBuffer.peek(ringBuffer.buffer, ringBuffer.workingHeadPos.value-1, ringBuffer.mask);
    }

}
