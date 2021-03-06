package com.ociweb.pronghorn.pipe;

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.pipe.token.OperatorMask;
import com.ociweb.pronghorn.pipe.token.TokenBuilder;
import com.ociweb.pronghorn.pipe.token.TypeMask;
import com.ociweb.pronghorn.pipe.util.PaddedAtomicLong;
import com.ociweb.pronghorn.util.Appendables;


//cas: comment -- general for full file.
//     -- It would be worthwhile running this through a code formatter to bring it in line with what the
//        typical Java dev would expect.  A couple of things to consider would be max line length (one of the asserts
//        makes it all the way to col. 244).   A truly curious thing is the lack of spaces around the
//        less-than/greater-than operators.  All the other ops seem to get a nice padding, but not these.  Unequal
//        treatment, it would seem.  More or less.
//     --  JavaDoc!  (obviously) I think I can do this for you.  I'm not sure when I can do it, but the class seems
//         mature enough that the public API should be well-documented.  (Although I'm not sure all the public API
//         really is public.)  There will be some "jdoc" comments below as hints for whoever does it.

/**
 *
 * Schema aware data pipe implemented as an internal pair of ring buffers.
 *
 * One ring holds all the fixed-length fields and the fixed-length meta data relating to the variable-length
 * (unstructured fields).  The other ring holds only bytes which back the variable-length fields like Strings or Images.
 *
 * The supported Schema is defined in the FieldReferenceOffsetManager passed in upon construction.  The Schema is
 * made up of Messages. Messages are made up of one or more fixed-length fragments.
 *
 * The Message fragments enable direct lookup of fields within sequences and enable the consumption of larger messages
 * than would fit within the defined limits of the buffers.
 *
 * @author Nathan Tippy
 *
 * //cas: this needs expanded explanation of what a slot is. (best if done above.)
 * Storage:
 *  int     - 1 slot
 *  long    - 2 slots, high then low
 *  text    - 2 slots, index then length  (if index is negative use constant array)
 *  decimal - 3 slots, exponent then long mantissa
 *
 *  SlabRing - structured fixed size records
 *  BlobRing - unstructured variable length field data 
 *
 * StructuredLayoutRing   - These have strong type definition per field in addition to being fixed-length.
 * UnstructuredLayoutRing - These are just bytes that are commonly UTF-8 encoded strings but may be image data or
 * even video/audio streams.
 *
 * @since 0.1
 *
 */
public class Pipe<T extends MessageSchema<T>> {

    private static final AtomicInteger pipeCounter = new AtomicInteger(0);
    
    /**
     * Holds the active head position information.
     */
    static class SlabRingHead {
        // Position used during creation of a message in the ring.
        final PaddedLong workingHeadPos;
        // Actual position of the next Write location.
        final AtomicLong headPos;

        SlabRingHead() {
            this.workingHeadPos = new PaddedLong();
            this.headPos = new PaddedAtomicLong();
        }
    }

    /**
     * Holds the active tail position information.
     */
    static class SlabRingTail {
        /**
         * The workingTailPosition is only to be used by the consuming thread. As values are read the tail is moved
         * forward.  Eventually the consumer finishes the read of the fragment and will use this working position as
         * the value to be published in order to inform the writer of this new free space.
         */
        final PaddedLong workingTailPos; // No need for an atomic operation since only one thread will ever use this.

        /**
         * This is the official published tail position. It is written to by the consuming thread and frequently
         * polled by the producing thread.  Making use of the built in CAS features of AtomicLong forms a memory
         * gate that enables this lock free implementation to function.
         */
        final AtomicLong tailPos;

        SlabRingTail() {
            this.workingTailPos = new PaddedLong();
            this.tailPos = new PaddedAtomicLong();
        }

        /**
         * Switch the working tail back to the published tail position.
         * Only used by the replay feature, not for general use.
         */
        // TODO: ?
        // Enforce the contract of replay-only.
		long rollBackWorking() {
			return workingTailPos.value = tailPos.get();
		}
    }


    /**
     * Spinning on a CAS AtomicLong leads to a lot of contention which will decrease performance.
     * Once we know that the producer can write up to a given position there is no need to keep polling until data
     * is written up to that point.  This class holds the head value until that position is reached.
     */
    static class LowLevelAPIWritePositionCache {
        /**
         * This is the position the producer is allowed to write up to before having to ask the CAS AtomicLong
         * for a new value.
         */
        long llwHeadPosCache;

        /**
         * Holds the last position that has been officially written.  The Low Level API uses the size of the next
         * fragment added to this value to determine if the next write will need to go past the cached head position
         * above.
         *
         * // TODO: reword "by the size of the fragment"?
         * Once it is known that the write will fit, this value is incremented by the size to confirm the write.
         * This is independent of the workingHeadPosition by design so we have two accounting mechanisms to help
         * detect errors.
         *
         * TODO:M add asserts that implement the claim found above in the comments.
         */
        long llrConfirmedPosition;

        // TODO: Determine is this is going to be used -- if not, delete it.
        LowLevelAPIWritePositionCache() {
        }
    }

    /**
     * Holds the tail value for the consumer.
     */
    static class LowLevelAPIReadPositionCache {
        long llrTailPosCache;
        /**
         * Holds the last position that has been officially read.
         */
        long llwConfirmedPosition;

        // TODO: Determine is this is going to be used -- if not, delete it.
        LowLevelAPIReadPositionCache() {
        }
    }

    /**
     * Serves the same function as the StructuredLayoutRingHead, but holds information for the UnstructuredLayoutRing.
     */
    static class BlobRingHead {
        final PaddedInt byteWorkingHeadPos;
        final PaddedInt bytesHeadPos;

        BlobRingHead() {
            this.byteWorkingHeadPos = new PaddedInt();
            this.bytesHeadPos = new PaddedInt();
        }
    }

    /**
     * Serves the same function as the StructuredLayoutRingTail, but holds information for the UnstructuredLayoutRing.
     */
    static class BlobRingTail {
        final PaddedInt byteWorkingTailPos;
        final PaddedInt bytesTailPos;

        BlobRingTail() {
            this.byteWorkingTailPos = new PaddedInt();
            this.bytesTailPos = new PaddedInt();
        }

        // TODO: The "only used by" needs to be enforced.
        /**
         * Switch the working tail back to the published tail position.
         * Only used by the replay feature, not for general use.
         */
		int rollBackWorking() {
			return byteWorkingTailPos.value = bytesTailPos.value;
		}
    }

    /**
     * Provides a container holding a long value that fills a 64-byte cache line.
     */
    public static class PaddedLong {
        // These primitives will be next to one another in memory if there are no other members of this object.
        // TODO: Is this public?
        public long value = 0, padding1, padding2, padding3, padding4, padding5, padding6, padding7;

        // The following accessor methods are static instead of instance methods because small static methods will
        // frequently be in-lined which allows direct access to the value member without method overhead.
        /**
         * Provides access to the value of this PaddedLong.
         * @param pl  is the PaddedLong containing the desired value.
         * @return    the value contained by the provided long.
         */
        public static long get(PaddedLong pl) {
            return pl.value;
        }

        /**
         * Sets the value of the provided PaddedLong.
         * @param pl     is the padded long to contain the value.
         * @param value  is the value to be put into the padded long.
         */
        public static void set(PaddedLong pl, long value) {
            pl.value = value;
        }

        /**
         * Adds the provided increment to the existing value of the long.
         * <b>N.B.</b> A PaddedLong is initialized to zero.  There is no problem invoking this method on a PaddedLong
         * that has never had the set method called.  It may not achieve the desired effect, but it will not cause a
         * runtime error.
         * @param pl   is the padded long containing the value to increment.
         * @param inc  is the amount to add.
         * @return     the incremented value of the provided padded long instance.
         */
        public static long add(PaddedLong pl, long inc) {
                return pl.value += inc;
        }

        /**
         * Provides a readable representation of the value of this padded long instance.
         * @return  a String of the Long value of this padded long instance.
         */
        public String toString() {
            return Long.toString(value);
        }

    }

    /**
     * Provides a container holding an int value that fills a 64-byte cache line.
     */
    public static class PaddedInt {
        // Most platforms have 64 byte cache lines so the value variable is padded so 16 four byte ints are consumed.
        // If a platform has smaller cache lines, this approach will use a little more memory than required but the
        // performance gains will still be preserved.
        // Modern Intel and AMD chips commonly have 64 byte cache lines.
        // TODO: code This should just be 15, shouldn't it?
        public int value = 0, padding1, padding2, padding3, padding4, padding5, padding6, padding7, padding8,
            padding9, padding10, padding11, padding13, padding14, padding15, padding16;

        /**
         * Provides access to the value of this PaddedInt.
         * @param pi  is the PaddedInt containing the desired value.
         * @return    the value contained by the provided int.
         */
		public static int get(PaddedInt pi) {
	            return pi.value;
	    }

        /**
         * Sets the value of the provided PaddedInt.
         * @param pi     is the padded int to contain the value.
         * @param value  is the value to be put into the padded int.
         */
		public static void set(PaddedInt pi, int value) {
		    pi.value = value;
		}

        /**
         * Adds the provided increment to the existing value of the int.
         * <b>N.B.</b> A PaddedInt is initialized to zero.  There is not problem invoking this method on a PaddedInt
         * that has never had the set method called.  It may not achieve the desired effect, but it will not cause a
         * runtime error.
         * @param pi   is the padded int containing the value to increment.
         * @param inc  is the amount to add.
         * @return     the incremented value of the provided padded int instance.
         */
	    public static int add(PaddedInt pi, int inc) {
	            return pi.value += inc;
	    }

        /**
         * Provides an increment routine to support the need to wrap the head and tail markers of a buffer from the
         * maximum int value to 0 without going negative. The method adds the provided increment to the existing value
         * of the provided PaddedInt. The resultant sum is <code>and</code>ed to the provided mask to remove any
         * sign bit that may have been set in the case of an overflow of the maximum-sized integer.
         * <b>N.B.</b> A PaddedInt is initialized to zero.  There is no problem invoking this method on a PaddedInt
         * that has never had the set method called.  It may not achieve the desired effect, but it will not cause a
         * runtime error.
         * @param pi   is the padded int containing the value to increment.
         * @param inc  is the amount to add.
         * @return     the incremented value of the provided padded int instance.
         */
	    public static int maskedAdd(PaddedInt pi, int inc, int wrapMask) {
               return pi.value = wrapMask & (inc + pi.value);
        }

        /**
         * Provides a readable representation of the value of this padded long instance.
         * @return  a String of the Long value of this padded long instance.
         */
		public String toString() {
		    return Integer.toString(value);
		}
    }

    private static final Logger log = LoggerFactory.getLogger(Pipe.class);

    //I would like to follow the convention where all caps constants are used to indicate static final values which are resolved at compile time.
    //This is distinct from other static finals which hold run time instances and values computed from runtime input.
    //The reason for this distinction is that these members have special properties.
    //    A) the literal value replaces the variable by the compiler so..   a change of value requires a recompile of all dependent jars.
    //    B) these are the only variables which are allowed as case values in switch statements.

    //This mask is used to filter the meta value used for variable-length fields.
    //after applying this mask to meta the result is always the relative offset within the byte buffer of where the variable-length data starts.
    //NOTE: when the high bit is set we will not pull the value from the ring buffer but instead use the constants array (these are pronouns)
    public static final int RELATIVE_POS_MASK = 0x7FFFFFFF; //removes high bit which indicates this is a constant

    //This mask is here to support the fact that variable-length fields will run out of space because the head/tail are 32 bit ints instead of
    //longs that are used for the structured layout data.  This mask enables the int to wrap back down to zero instead of going negative.
    //this will only happen once for every 2GB written.
    public static final int BYTES_WRAP_MASK = 0x7FFFFFFF;//NOTE: this trick only works because its 1 bit less than the roll-over sign bit

    //A few corner use cases require a poison pill EOF message to be sent down the pipes to ensure each consumer knows when to shut down.
    //This is here for compatibility with legacy APIs,  This constant is the size of the EOF message.
    public static final int EOF_SIZE = 2;

    //these public fields are fine because they are all final
    public final int id;
    public final int sizeOfSlabRing;
    public final int sizeOfBlobRing;
    @Deprecated
    public final int mask;
    public final int slabMask;
    @Deprecated
    public final int byteMask;
    public final int blobMask;
    
    public final byte bitsOfSlabRing;
    public final byte bitsOfBlogRing;
    
    @Deprecated //old name
    public final int maxAvgVarLen;
    public final int maxVarLen;//to be used when copying data in dense chunks.
    private final T schema;
    
    final boolean usingHighLevelAPI;
    

    //TODO: B, need to add constant for gap always kept after head and before tail, this is for debug mode to store old state upon error. NEW FEATURE.
    //            the time slices of the graph will need to be kept for all rings to reconstruct history later.


    private final SlabRingHead slabRingHead = new SlabRingHead();
    private final BlobRingHead blobRingHead = new BlobRingHead();

    LowLevelAPIWritePositionCache llWrite; //low level write head pos cache and target
    LowLevelAPIReadPositionCache llRead; //low level read tail pos cache and target

    //TODO: C, add a configuration to disable this construction when we know it s not used.
    StackStateWalker ringWalker;//only needed for high level API
    
    //TODO: C, add a configuration to disable this construction when we know it s not used.
    PendingReleaseData pendingReleases;//only used when we want to release blob data async from our walking of each fragment
    

    final SlabRingTail slabRingTail = new SlabRingTail(); //primary working and public
    private final BlobRingTail blobRingTail = new BlobRingTail(); //primary working and public

    //these values are only modified and used when replay is NOT in use
    //hold the publish position when batching so the batch can be flushed upon shutdown and thread context switches
    private int lastReleasedBlobTail;
    long lastReleasedSlabTail;

    int blobWriteLastConsumedPos = 0;

    //All references found in the messages/fragments to variable-length content are relative.  These members hold the current
    //base offset to which the relative value is added to find the absolute position in the ring.
    //These values are only updated as each fragment is consumed or produced.
    private int blobWriteBase = 0;
    private int blobReadBase = 0;

    //Non Uniform Memory Architectures (NUMA) are supported by the Java Virtual Machine(JVM)
    //However there are some limitations.
    //   A) NUMA support must be enabled with the command line argument
    //   B) The heap space must be allocated by the same thread which expects to use it long term.
    //
    // As a result of the above the construction of the buffers is postponed and done with an initBuffers() method.
    // The initBuffers() method will be called by the consuming thread before the pipe is used. (see Pronghorn)
    public byte[] blobRing; //TODO: B, these two must remain public until the meta/sql modules are fully integrated.
    private int[] slabRing;
    //defined externally and never changes
    protected final byte[] blobConstBuffer;
    private byte[][] blobRingLookup;
    
    
    //NOTE:
    //     These are provided for seamless integration with the APIs that use ByteBuffers
    //     This include the SSLEngine and SocketChanels and many other NIO APIs
    //     For performance reasons ByteBuffers should not be used unless the API has no other integration points.
    //     Using the Pipes directly is more perfomrmant and the use of DataOutput and DataInput should also be considered.

    private IntBuffer wrappedSlabRing;
    private ByteBuffer wrappedBlobReadingRingA;
    private ByteBuffer wrappedBlobReadingRingB;
    
    private ByteBuffer wrappedBlobWritingRingA;
    private ByteBuffer wrappedBlobWritingRingB;
    
    private ByteBuffer[] wrappedWritingBuffers;
    private ByteBuffer[] wrappedReadingBuffers;
    
    
    private ByteBuffer wrappedBlobConstBuffer;
    
    //      These are the recommended objects to be used for reading and writing streams into the blob
    private DataOutputBlobWriter<T> blobWriter;
    private DataInputBlobReader<T> blobReader;
    
    

    //for writes validates that bytes of var length field is within the expected bounds.
    private int varLenMovingAverage = 0;//this is an exponential moving average

    static final int JUMP_MASK = 0xFFFFF;

    //Exceptions must not occur within consumers/producers of rings however when they do we no longer have
    //a clean understanding of state. To resolve the problem all producers and consumers must also shutdown.
    //This flag passes the signal so any producer/consumer that sees it on knows to shut down and pass on the flag.
    private final AtomicBoolean imperativeShutDown = new AtomicBoolean(false);
    private PipeException firstShutdownCaller = null;


	//hold the batch positions, when the number reaches zero the records are send or released
	private int batchReleaseCountDown = 0;
	int batchReleaseCountDownInit = 0;
	private int batchPublishCountDown = 0;
	private int batchPublishCountDownInit = 0;
	//cas: jdoc -- This is the first mention of batch(ing).  It would really help the maintainer's comprehension of what
	// you mean if you would explain this hugely overloaded word somewhere prior to use -- probably in the class's javadoc.
	    //hold the publish position when batching so the batch can be flushed upon shutdown and thread context switches
    private int lastPublishedBlobRingHead;
    private long lastPublishedSlabRingHead;

	private final int debugFlags;

	private long holdingSlabWorkingTail;
	private int  holdingBlobWorkingTail;
	private int holdingBlobReadBase;

    private PipeRegulator regulatorConsumer; //disabled by default
    private PipeRegulator regulatorProducer; //disabled by default

    //for monitoring only
    public int lastMsgIdx; //last msgId read

    //helper method for those stages that are not watching for the poison pill.
	private long knownPositionOfEOF = Long.MAX_VALUE;
	
    
    private long markedHeadSlab;
    private int markedHeadBlob;
    
    private long markedTailSlab;
    private int markedTailBlob;

    private int activeBlobHead = -1;
	
	//////////////////
    /////////////////
	private static ThreadBasedCallerLookup callerLookup;
	
	//for debugging
	public static void setThreadCallerLookup(ThreadBasedCallerLookup callerLookup) {
		Pipe.callerLookup = callerLookup;
	}
	
	static boolean singleThreadPerPipeWrite(int pipeId) {
		if (null != callerLookup) {			
			int callerId = callerLookup.getCallerId();
			if (callerId>=0) {
				int expected = callerLookup.getProducerId(pipeId);
				if (expected>=0) {
					assert(callerId == expected) : "Check your graph construction and stage constructors.\n Pipe "+pipeId+" must only have 1 stage therefore 1 thread writing to it.";
				}
			}
		}
		return true;
	}

	static boolean singleThreadPerPipeRead(int pipeId) {
		if (null != callerLookup) {			
			int callerId = callerLookup.getCallerId();
			if (callerId>=0) {
				int expected = callerLookup.getConsumerId(pipeId);
				if (expected>=0) {
					assert(callerId == expected) : "Check your graph construction and stage constructors.\n Pipe "+pipeId+" must only have 1 stage therefore 1 thread reading from it.";
				}
			}
		}
		return true;
	}
    
	////////////////////
	////////////////////
	public Pipe(PipeConfig<T> config) {
		this(config,true);
	}
    	
    public Pipe(PipeConfig<T> config, boolean usingHighLevelAPI) {

    	this.usingHighLevelAPI = usingHighLevelAPI;
        byte primaryBits = config.slabBits;
        byte byteBits = config.blobBits;
        byte[] byteConstants = config.byteConst;
        this.schema = config.schema;

        debugFlags = config.debugFlags;
                

        //Assign the immutable universal id value for this specific instance
        //these values are required to keep track of all ring buffers when graphs are built
        this.id = pipeCounter.getAndIncrement();

        this.bitsOfSlabRing = primaryBits;
        this.bitsOfBlogRing = byteBits;

        assert (primaryBits >= 0); //zero is a special case for a mock ring

//cas: naming.  This should be consistent with the maxByteSize, i.e., maxFixedSize or whatever.
        //single buffer size for every nested set of groups, must be set to support the largest need.
        this.sizeOfSlabRing = 1 << primaryBits;
        this.slabMask = Math.max(1, sizeOfSlabRing - 1);  //mask can no be any smaller than 1
        this.mask = slabMask;
        //single text and byte buffers because this is where the variable-length data will go.

        this.sizeOfBlobRing =  1 << byteBits;
        this.blobMask = Math.max(1, sizeOfBlobRing - 1); //mask can no be any smaller than 1
        this.byteMask= blobMask;
        FieldReferenceOffsetManager from = MessageSchema.from(config.schema); 

        this.blobConstBuffer = byteConstants;


        if (0 == from.maxVarFieldPerUnit || 0==primaryBits) { //zero bits is for the dummy mock case
            maxAvgVarLen = 0; //no fragments had any variable-length fields so we never allow any
        } else {
            //given outer ring buffer this is the maximum number of var fields that can exist at the same time.
            int maxVarCount = FieldReferenceOffsetManager.maxVarLenFieldsPerPrimaryRingSize(from, sizeOfSlabRing);
            //to allow more almost 2x more flexibility in variable-length bytes we track pairs of writes and ensure the
            //two together are below the threshold rather than each alone
            maxAvgVarLen = blobMask/maxVarCount;
        }
        maxVarLen = maxAvgVarLen;
    }
 
    private AtomicBoolean isInBlobFieldWrite = new AtomicBoolean(false);
    
    public static <S extends MessageSchema<S>> boolean isInBlobFieldWrite(Pipe<S> pipe) {
        return pipe.isInBlobFieldWrite.get();
    }
    
    public void openBlobFieldWrite() {        
        if (!isInBlobFieldWrite.compareAndSet(false, true)) {
            throw new UnsupportedOperationException("only one open write against the blob at a time.");
        }        
    }

    public void closeBlobFieldWrite() {
        if (!isInBlobFieldWrite.compareAndSet(true, false)) {
            throw new UnsupportedOperationException("can not close blob if not open.");
        }
    }
    
    //NOTE: can we compute the speed limit based on destination CPU Usage?
    //TODO: add checking mode where it can communicate back that regulation is too big or too small?
    
    public static <S extends MessageSchema<S>> boolean isRateLimitedConsumer(Pipe<S> pipe) {
        return null!=pipe.regulatorConsumer;
    }
    
    public static <S extends MessageSchema<S>> boolean isRateLimitedProducer(Pipe<S> pipe) {
        return null!=pipe.regulatorProducer;
    }
    
    /**
     * Returns nano-second count of how much time should pass before consuming more data 
     * from this pipe.  This is based on the rate configuration.  
     */
    public static <S extends MessageSchema<S>> long computeRateLimitConsumerDelay(Pipe<S> pipe) {        
        return PipeRegulator.computeRateLimitDelay(pipe, Pipe.getWorkingTailPosition(pipe), pipe.regulatorConsumer);
    }

    /**
     * Returns nano-second count of how much time should pass before producing more data 
     * into this pipe.  This is based on the rate configuration.  
     */
    public static <S extends MessageSchema<S>> long computeRateLimitProducerDelay(Pipe<S> pipe) {        
        return PipeRegulator.computeRateLimitDelay(pipe, Pipe.workingHeadPosition(pipe), pipe.regulatorProducer);
    }
    
    public static <S extends MessageSchema<S>, T extends MessageSchema<T>> boolean isForSchema(Pipe<S> pipe, T schema) {
        return pipe.schema == schema;
    }
    
    public static <S extends MessageSchema<S>, T extends MessageSchema<T>> boolean isForSchema(Pipe<S> pipe, Class<T> schema) {
        return schema.isInstance(pipe.schema);
    }
    
    public static <S extends MessageSchema<S>, T extends MessageSchema<T>> boolean isForSameSchema(Pipe<S> pipeA, Pipe<T> pipeB) {
        return pipeA.schema == pipeB.schema;
    }
    
    public static <S extends MessageSchema<S>> boolean isForDynamicSchema(Pipe<S> pipe) {
        return pipe.schema instanceof MessageSchemaDynamic;
    }
    
    public static <S extends MessageSchema<S>> String schemaName(Pipe<S> pipe) {
        return null==pipe.schema? "NoSchemaFor "+Pipe.from(pipe).name  :pipe.schema.getClass().getSimpleName();
    }
    
	public static <S extends MessageSchema<S>> void replayUnReleased(Pipe<S> ringBuffer) {

//We must enforce this but we have a few unit tests that are in violation which need to be fixed first
//	    if (!RingBuffer.from(ringBuffer).hasSimpleMessagesOnly) {
//	        throw new UnsupportedOperationException("replay of unreleased messages is not supported unless every message is also a single fragment.");
//	    }

		if (!isReplaying(ringBuffer)) {
			//save all working values only once if we re-enter replaying multiple times.

		    ringBuffer.holdingSlabWorkingTail = Pipe.getWorkingTailPosition(ringBuffer);
			ringBuffer.holdingBlobWorkingTail = Pipe.getWorkingBlobRingTailPosition(ringBuffer);

			//NOTE: we must never adjust the ringWalker.nextWorkingHead because this is replay and must not modify write position!
			ringBuffer.ringWalker.holdingNextWorkingTail = ringBuffer.ringWalker.nextWorkingTail;

			ringBuffer.holdingBlobReadBase = ringBuffer.blobReadBase;

		}

		//clears the stack and cursor position back to -1 so we assume that the next read will begin a new message
		StackStateWalker.resetCursorState(ringBuffer.ringWalker);

		//set new position values for high and low api
		ringBuffer.ringWalker.nextWorkingTail = ringBuffer.slabRingTail.rollBackWorking();
		ringBuffer.blobReadBase = ringBuffer.blobRingTail.rollBackWorking(); //this byte position is used by both high and low api
	}

/**
 * Returns <code>true</code> if the provided pipe is replaying.
 *
 * @param ringBuffer  the ringBuffer to check.
 * @return            <code>true</code> if the ringBuffer is replaying, <code>false</code> if it is not.
 */
	public static <S extends MessageSchema<S>> boolean isReplaying(Pipe<S> ringBuffer) {
		return Pipe.getWorkingTailPosition(ringBuffer)<ringBuffer.holdingSlabWorkingTail;
	}

	public static <S extends MessageSchema<S>> void cancelReplay(Pipe<S> ringBuffer) {
		ringBuffer.slabRingTail.workingTailPos.value = ringBuffer.holdingSlabWorkingTail;
		ringBuffer.blobRingTail.byteWorkingTailPos.value = ringBuffer.holdingBlobWorkingTail;

		ringBuffer.blobReadBase = ringBuffer.holdingBlobReadBase;

		ringBuffer.ringWalker.nextWorkingTail = ringBuffer.ringWalker.holdingNextWorkingTail;
		//NOTE while replay is in effect the head can be moved by the other (writing) thread.
	}

	////
	////
	public static <S extends MessageSchema<S>> void batchAllReleases(Pipe<S> rb) {
	   rb.batchReleaseCountDownInit = Integer.MAX_VALUE;
	   rb.batchReleaseCountDown = Integer.MAX_VALUE;
	}


    public static <S extends MessageSchema<S>> void setReleaseBatchSize(Pipe<S> pipe, int size) {

    	validateBatchSize(pipe, size);

    	pipe.batchReleaseCountDownInit = size;
    	pipe.batchReleaseCountDown = size;
    }

    public static <S extends MessageSchema<S>> void setPublishBatchSize(Pipe<S> pipe, int size) {

    	validateBatchSize(pipe, size);

    	pipe.batchPublishCountDownInit = size;
    	pipe.batchPublishCountDown = size;
    }
    
    public static <S extends MessageSchema<S>> int getPublishBatchSize(Pipe<S> pipe) {
        return pipe.batchPublishCountDownInit;
    }
    
    public static <S extends MessageSchema<S>> int getReleaseBatchSize(Pipe<S> pipe) {
        return pipe.batchReleaseCountDownInit;
    }

    public static <S extends MessageSchema<S>> void setMaxPublishBatchSize(Pipe<S> rb) {

    	int size = computeMaxBatchSize(rb, 3);

    	rb.batchPublishCountDownInit = size;
    	rb.batchPublishCountDown = size;

    }

    public static <S extends MessageSchema<S>> void setMaxReleaseBatchSize(Pipe<S> rb) {

    	int size = computeMaxBatchSize(rb, 3);
    	rb.batchReleaseCountDownInit = size;
    	rb.batchReleaseCountDown = size;

    }


//cas: naming -- a couple of things, neither new.  Obviously the name of the buffer, bytes.  Also the use of base in
// the variable buffer, but not in the fixed.  Otoh, by now, maybe the interested reader would already understand.
    public static <S extends MessageSchema<S>> int bytesWriteBase(Pipe<S> rb) {
    	return rb.blobWriteBase;
    }

    public static <S extends MessageSchema<S>> void markBytesWriteBase(Pipe<S> rb) {
    	rb.blobWriteBase = rb.blobRingHead.byteWorkingHeadPos.value;
    }

    public static <S extends MessageSchema<S>> int bytesReadBase(Pipe<S> pipe) {
          
        assert(validateInsideData(pipe, pipe.blobReadBase));
        
    	return pipe.blobReadBase;
    }        
    
    private static <S extends MessageSchema<S>> boolean validateInsideData(Pipe<S> pipe, int value) {
		

	    int mHead = Pipe.blobMask(pipe) & Pipe.getBlobHeadPosition(pipe);
	    int mTail = Pipe.blobMask(pipe) & Pipe.getBlobTailPosition(pipe);
	    int mValue = Pipe.blobMask(pipe) & value;
	    if (mTail<=mHead) {
	    	assert(mTail<=mValue && mValue<=mHead) : "tail "+mTail+" readBase "+mValue+" head "+mHead;
	        return mTail<=mValue && mValue<=mHead;
	    } else {
	    	assert(mValue<=mHead || mValue>=mTail) : "tail "+mTail+" readBase "+mValue+" head "+mHead;
	    	return mValue<=mHead || mValue>=mTail;
	    }
	}

	public static <S extends MessageSchema<S>> void markBytesReadBase(Pipe<S> pipe, int bytesConsumed) {
        assert(bytesConsumed>=0) : "Bytes consumed must be positive";
        //base has future pos added to it so this value must be masked and kept as small as possible
                
        pipe.blobReadBase = Pipe.BYTES_WRAP_MASK & (pipe.blobReadBase+bytesConsumed);
        assert(validateInsideData(pipe, pipe.blobReadBase)) : "consumed "+bytesConsumed+" bytes using mask "+pipe.blobMask+" new base is "+pipe.blobReadBase;
    }
    
    public static <S extends MessageSchema<S>> void markBytesReadBase(Pipe<S> pipe) {
        //base has future pos added to it so this value must be masked and kept as small as possible
        pipe.blobReadBase = pipe.blobMask & PaddedInt.get(pipe.blobRingTail.byteWorkingTailPos);
        assert(validateInsideData(pipe, pipe.blobReadBase));
    }
    
    //;

    /**
     * Helpful user readable summary of the ring buffer.
     * Shows where the head and tail positions are along with how full the ring is at the time of call.
     */
    public String toString() {

        int contentRem = Pipe.contentRemaining(this);
        assert(contentRem <= sizeOfSlabRing) : "ERROR: can not have more content than the size of the pipe. content "+contentRem+" vs "+sizeOfSlabRing;
        
    	StringBuilder result = new StringBuilder();
    	result.append("RingId<").append(schemaName(this));
    	result.append(">:").append(id);
    	result.append(" slabTailPos ").append(slabRingTail.tailPos.get());
    	result.append(" slabWrkTailPos ").append(slabRingTail.workingTailPos.value);
    	result.append(" slabHeadPos ").append(slabRingHead.headPos.get());
    	result.append(" slabWrkHeadPos ").append(slabRingHead.workingHeadPos.value);
    	result.append("  ").append(contentRem).append("/").append(sizeOfSlabRing);
    	result.append("  blobTailPos ").append(PaddedInt.get(blobRingTail.bytesTailPos));
    	result.append(" blobWrkTailPos ").append(blobRingTail.byteWorkingTailPos.value);
    	result.append(" blobHeadPos ").append(PaddedInt.get(blobRingHead.bytesHeadPos));
    	result.append(" blobWrkHeadPos ").append(blobRingHead.byteWorkingHeadPos.value);
    	
    	if (isEndOfPipe(this, slabRingTail.tailPos.get())) {
    		result.append(" Ended at "+this.knownPositionOfEOF);
    	}

    	return result.toString();
    }


    /**
     * Return the configuration used for this ring buffer, Helpful when we need to make clones of the ring which will hold same message types.
     */
    public PipeConfig<T> config() {
        //TODO:M, this creates garbage and we should just hold the config object instead of copying the values out.  Then return the same instance here.
        return new PipeConfig<T>(bitsOfSlabRing, bitsOfBlogRing, blobConstBuffer, schema);
    }



    public static <S extends MessageSchema> int totalPipes() {
        return pipeCounter.get();
    }

    @Deprecated
    public static <S extends MessageSchema<S>> int totalRings() {
        return totalPipes();
    }

	public Pipe<T> initBuffers() {
		assert(!isInit(this)) : "RingBuffer was already initialized";
		if (!isInit(this)) {
			buildBuffers();
		} else {
			log.warn("Init was already called once already on this ring buffer");
		}
		return this;
    }
    
    public static <S extends MessageSchema<S>> void setConsumerRegulation(Pipe<S> pipe, int msgPerMs, int msgSize) {
        assert(null==pipe.regulatorConsumer) : "regulator must only be set once";
        assert(!isInit(pipe)) : "regular may only be set before scheduler has intitailized the pipe";
        pipe.regulatorConsumer = new PipeRegulator(msgPerMs, msgSize);
    }
  
    public static <S extends MessageSchema<S>> void setProducerRegulation(Pipe<S> pipe, int msgPerMs, int msgSize) {
        assert(null==pipe.regulatorProducer) : "regulator must only be set once";
        assert(!isInit(pipe)) : "regular may only be set before scheduler has intitailized the pipe";
        pipe.regulatorProducer = new PipeRegulator(msgPerMs, msgSize);
    } 
    
	private void buildBuffers() {

	    this.pendingReleases = new PendingReleaseData(sizeOfSlabRing/FieldReferenceOffsetManager.minFragmentSize(MessageSchema.from(schema)));
	    
	    
	    //NOTE: this is only needed for high level API, if only low level is in use it would be nice to not create this 
	    if (usingHighLevelAPI) {
	    	this.ringWalker = new StackStateWalker(MessageSchema.from(schema), sizeOfSlabRing);
	    }
	    
        assert(slabRingHead.workingHeadPos.value == slabRingHead.headPos.get());
        assert(slabRingTail.workingTailPos.value == slabRingTail.tailPos.get());
        assert(slabRingHead.workingHeadPos.value == slabRingTail.workingTailPos.value);
        assert(slabRingTail.tailPos.get()==slabRingHead.headPos.get());

        long toPos = slabRingHead.workingHeadPos.value;//can use this now that we have confirmed they all match.

        this.llRead = new LowLevelAPIReadPositionCache();
        this.llWrite = new LowLevelAPIWritePositionCache();

        //This init must be the same as what is done in reset()
        //This target is a counter that marks if there is room to write more data into the ring without overwriting other data.
        llWrite.llwHeadPosCache = toPos;
        llRead.llrTailPosCache = toPos;
        llRead.llwConfirmedPosition = toPos - sizeOfSlabRing;// TODO: hack test,  mask;//must be mask to ensure zero case works.
        llWrite.llrConfirmedPosition = toPos;

        try {
	        this.blobRing = new byte[sizeOfBlobRing];
	        this.slabRing = new int[sizeOfSlabRing];
	        this.blobRingLookup = new byte[][] {blobRing,blobConstBuffer};
        } catch (OutOfMemoryError oome) {
        	
        	log.warn("attempted to allocate Slab:{} Blob:{} in {}", sizeOfSlabRing, sizeOfBlobRing, this, oome);
        	shutdown(this);
        	System.exit(-1);
        }
        //This assignment is critical to knowing that init was called
        this.wrappedSlabRing = IntBuffer.wrap(this.slabRing);        

        //only create if there is a possiblity that they may be used.
        if (sizeOfBlobRing>0) {
	        this.wrappedBlobReadingRingA = ByteBuffer.wrap(this.blobRing);
	        this.wrappedBlobReadingRingB = ByteBuffer.wrap(this.blobRing);
	        this.wrappedBlobWritingRingA = ByteBuffer.wrap(this.blobRing);
	        this.wrappedBlobWritingRingB = ByteBuffer.wrap(this.blobRing);	        
	        this.wrappedBlobConstBuffer = null==this.blobConstBuffer?null:ByteBuffer.wrap(this.blobConstBuffer);
	        
	        this.wrappedReadingBuffers = new ByteBuffer[]{wrappedBlobReadingRingA,wrappedBlobReadingRingB}; 
	        this.wrappedWritingBuffers = new ByteBuffer[]{wrappedBlobWritingRingA,wrappedBlobWritingRingB};
	        
	        
	        assert(0==wrappedBlobReadingRingA.position() && wrappedBlobReadingRingA.capacity()==wrappedBlobReadingRingA.limit()) : "The ByteBuffer is not clear.";
	
	        //blobReader and writer must be last since they will be checking isInit in construction.
	        this.blobReader = createNewBlobReader();
	        this.blobWriter = createNewBlobWriter();
        }
	}
	
	//Can be overridden to support specific classes which extend DataInputBlobReader
	protected DataInputBlobReader<T> createNewBlobReader() {
		 return new DataInputBlobReader<T>(this);
	}
	
	//Can be overridden to support specific classes which extend DataOutputBlobWriter
	protected DataOutputBlobWriter<T> createNewBlobWriter() {
		return new DataOutputBlobWriter<T>(this);
	}
	

	public static <S extends MessageSchema<S>> boolean isInit(Pipe<S> ring) {
	    //Due to the fact that no locks are used it becomes necessary to check
	    //every single field to ensure the full initialization of the object
	    //this is done as part of graph set up and as such is called rarely.
		return null!=ring.blobRing &&
			   null!=ring.slabRing &&
			   null!=ring.blobRingLookup &&
			   null!=ring.wrappedSlabRing &&
			   null!=ring.llRead &&
			   null!=ring.llWrite &&
			   (
			    ring.sizeOfBlobRing == 0 || //no init of these if the blob is not used
			    (null!=ring.wrappedBlobReadingRingA &&
		         null!=ring.wrappedBlobReadingRingB &&
			     null!=ring.wrappedBlobWritingRingA &&
			     null!=ring.wrappedBlobWritingRingB
			     )
			   );
		      //blobReader and blobWriter and not checked since they call isInit on construction
	}

	public static <S extends MessageSchema<S>> boolean validateVarLength(Pipe<S> pipe, int length) {
		int newAvg = (length+pipe.varLenMovingAverage)>>1;
        if (newAvg>pipe.maxVarLen)	{
            //compute some helpful information to add to the exception
        	int bytesPerInt = (int)Math.ceil(length*Pipe.from(pipe).maxVarFieldPerUnit);
        	int bitsDif = 32 - Integer.numberOfLeadingZeros(bytesPerInt - 1);
        	Pipe.shutdown(pipe);
        	throw new UnsupportedOperationException("Can not write byte array of length "+length+
        	                                        ". The dif between slab and byte blob should be at least "+bitsDif+
        	                                        ". "+pipe.bitsOfSlabRing+","+pipe.bitsOfBlogRing+
        	                                        ". The limit is "+pipe.maxVarLen+" for pipe "+pipe);
        }
        pipe.varLenMovingAverage = newAvg;
        return true;
	}



    /**
     * Empty and restore to original values.
     */
    public void reset() {
    	reset(0,0);
    }

    /**
     * Rest to desired position, helpful in unit testing to force wrap off the end.
     * @param structuredPos
     */
    public void reset(int structuredPos, int unstructuredPos) {

    	slabRingHead.workingHeadPos.value = structuredPos;
        slabRingTail.workingTailPos.value = structuredPos;
        slabRingTail.tailPos.set(structuredPos);
        slabRingHead.headPos.set(structuredPos);

        if (null!=llWrite) {
            llWrite.llwHeadPosCache = structuredPos;
            llRead.llrTailPosCache = structuredPos;
            llRead.llwConfirmedPosition = structuredPos -  sizeOfSlabRing;//mask;sss  TODO: hack test.
            llWrite.llrConfirmedPosition = structuredPos;
        }

        blobRingHead.byteWorkingHeadPos.value = unstructuredPos;
        PaddedInt.set(blobRingHead.bytesHeadPos,unstructuredPos);

        blobWriteBase = unstructuredPos;
        blobReadBase = unstructuredPos;
        blobWriteLastConsumedPos = unstructuredPos;
        

        blobRingTail.byteWorkingTailPos.value = unstructuredPos;
        PaddedInt.set(blobRingTail.bytesTailPos,unstructuredPos);
        StackStateWalker.reset(ringWalker, structuredPos);
    }

	public static Pipe[] buildPipes(PipeConfig[] configs) {
		int i = configs.length;
		Pipe[] result = new Pipe[i];
		while (--i>=0) {
			result[i] = new Pipe(configs[i]);
		}		
		return result;
	}

	public static <S extends MessageSchema<S>> Pipe<S>[] buildPipes(int count, PipeConfig<S> comonConfig) {		
		Pipe[] result = new Pipe[count];
		int i = count;
		while (--i>=0) {
			result[i] = new Pipe<S>(comonConfig);
		}
		return (Pipe<S>[])result;
	}

	public static <S extends MessageSchema<S>> boolean validatePipeBlobHasDataToRead(Pipe<S> pipe, int blobPos, int length) {

		assert(length>=0) : "bad lenght:"+length;
		if (length==0) {			
			return true;//nothing to check in this case.
		}
		
		//we know that we are looking for a non zero length
		assert(Pipe.getBlobHeadPosition(pipe)!=Pipe.getBlobTailPosition(pipe)) : "Needs "+length+" but pipe is empty and can not have any data: "+pipe;
		
		
	    int mHead = Pipe.blobMask(pipe) & Pipe.getBlobHeadPosition(pipe);
	    int mTail = Pipe.blobMask(pipe) & Pipe.getBlobTailPosition(pipe);
	    //ensure that starting at testPos up to testLen is all contained between tail and head
	        
	    int mStart = Pipe.blobMask(pipe) & blobPos;
	    int stop = mStart+length;
	    int mStop = Pipe.blobMask(pipe) & stop;
	         
	    //we have 4 cases where position and length can be inside (inbetween tail and head)
	    //these cases are all drawn below  pllll is the content starting at position p and
	    //running with p for the full length
	    
	    //Head - where new data can be written (can only write up to tail)
	    //Tail - where data is consumed (can only consume up to head)
	    
	    
	    if (stop >= pipe.sizeOfBlobRing) {
					////////////////////////////////////////////////////////////
					//pppppppppppp      H                      T     ppppppppp//
					////////////////////////////////////////////////////////////
	        assert(mStop<=mHead)  : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	        assert(mTail<=mStart) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	        assert(mHead<=mStart) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	        assert(mHead<mTail)   : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	        assert(mStop<=mHead)  : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	       
	        return (mStop<=mHead)
	        		&&(mTail<=mStart)
	        		&&(mHead<=mStart)
	        		&&(mHead<mTail)
	        		&&(mStop<=mHead);
	        
	    } else {
	      if (mHead>mTail) {
					////////////////////////////////////////////////////////////
					//        T     pppppppppppppppppppppp            H       //
					////////////////////////////////////////////////////////////
	    	 assert(mTail<mHead)  : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	    	 assert(mTail<=mStart): "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	    	 assert(mTail<=mStop) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	    	 assert(mStop<=mHead) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	    	 assert(stop<=pipe.sizeOfBlobRing) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	    	  
	    	 return (mTail<mHead)
	    			 &&(mTail<=mStart)
	    			 &&(mTail<=mStop)
	    			 &&(mStop<=mHead)
	    			 &&(stop<=pipe.sizeOfBlobRing);
	    	 
	      } else {
				if (mStart>=mTail) {
		    	  
					////////////////////////////////////////////////////////////
					//                  H                      T     pppppppp //
					////////////////////////////////////////////////////////////
				    assert(mHead<mTail) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
				    assert(mTail<=mStart): "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;			
					assert(stop<=pipe.sizeOfBlobRing) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
					
					return (mHead<mTail)
							&&(mTail<=mStart)
							&&(stop<=pipe.sizeOfBlobRing);
					
				} else {
				    	  
					////////////////////////////////////////////////////////////
					//pppppppppppp      H                      T              //
					////////////////////////////////////////////////////////////
				    assert(mStart<mHead) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
				    assert(mStop<=mHead) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
				    assert(mHead<mTail)  : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
					assert(stop<=pipe.sizeOfBlobRing) : "tail "+mTail+" start "+mStart+" stop "+mStop+" head "+mHead+" mask "+pipe.blobMask+" pipe "+pipe;
	
					return (mStart<mHead)
							&&(mStop<=mHead)
							&&(mHead<mTail)
							&&(stop<=pipe.sizeOfBlobRing);
					
				}
	      }
	    }
	}

	public static <S extends MessageSchema<S>> int addIntAsASCII(Pipe<S> output, int value) {
		validateVarLength(output, 12);
		return addLongAsUTF8(output, value);
	}

	
    public static <S extends MessageSchema<S>> int addRationalAsASCII(Pipe<S> digitBuffer, long numerator, long denominator) {
  	      
    	  validateVarLength(digitBuffer, 21);
	      DataOutputBlobWriter<S> outputStream = Pipe.outputStream(digitBuffer);
	      outputStream.openField();
	      
	      Appendables.appendValue(outputStream, numerator);
	      outputStream.writeChar('/');
	      Appendables.appendValue(outputStream, denominator);
	      	      
	      return outputStream.closeLowLevelField();	
	
    }
    
    
	public static <S extends MessageSchema<S>> int addLongAsASCII(Pipe<S> output, long value) {
		return addLongAsUTF8(output, value);
	}

    public static <S extends MessageSchema<S>> int addLongAsUTF8(Pipe<S> digitBuffer, long length) {
    	  validateVarLength(digitBuffer, 21);
	      DataOutputBlobWriter<S> outputStream = Pipe.outputStream(digitBuffer);
	      outputStream.openField();
	      Appendables.appendValue(outputStream, length);
	      return outputStream.closeLowLevelField();	
	}

    public static <S extends MessageSchema<S>> int addLongAsUTF8(Pipe<S> digitBuffer, int length) {
  	      validateVarLength(digitBuffer, 21);
	      DataOutputBlobWriter<S> outputStream = Pipe.outputStream(digitBuffer);
	      outputStream.openField();
	      Appendables.appendValue(outputStream, length);
	      return outputStream.closeLowLevelField();	
	}
    
	public static <S extends MessageSchema<S>> Pipe<S>[][] splitPipes(int pipeCount, Pipe<S>[] socketResponse) {
		
		Pipe<S>[][] result = new Pipe[pipeCount][];
			
		int fullLen = socketResponse.length;
		int last = 0;
		for(int p = 1;p<pipeCount;p++) {			
			int nextLimit = (p*fullLen)/pipeCount;			
			int plen = nextLimit-last;			
		    Pipe<S>[] newPipe = new Pipe[plen];
		    System.arraycopy(socketResponse, last, newPipe, 0, plen);
		    result[p-1]=newPipe;
			last = nextLimit;
		}
		int plen = fullLen-last;
	    Pipe<S>[] newPipe = new Pipe[plen];
	    System.arraycopy(socketResponse, last, newPipe, 0, plen);
	    result[pipeCount-1]=newPipe;
				
		return result;
				
	}
    
    //TODO: URGENT we need a unit test to ensure split pipes and split groups give the same splitting results.
    
    /**
     * matching the above splitPipes logic this method produces an inverse lookup array to determine group given a single index.
     * 
     * @param groups
     * @param fullLen
     * @return array to look up which group a value is in
     */
    public static int[] splitGroups(final int groups, final int fullLen) {
		
    	int c = 0;
		int[] result = new int[fullLen];
			
		int last = 0;
		
		for(int p = 1;p<groups;p++) {			
			int nextLimit = (p*fullLen)/groups;			
			int plen = nextLimit-last;	
			
			while (--plen>=0) {
				result[c++] = p-1;
			}
		    
			last = nextLimit;
		}
		int plen = fullLen-last;
	    
		while (--plen>=0) {
			result[c++] = groups-1;
		}
		
		return result;
				
	}
    

	public static <S extends MessageSchema<S>> void writeFieldToOutputStream(Pipe<S> pipe, OutputStream out) throws IOException {
        int meta = Pipe.takeRingByteMetaData(pipe);
        int length    = Pipe.takeRingByteLen(pipe);    
        if (length>0) {                
            int off = bytePosition(meta,pipe,length) & Pipe.blobMask(pipe);
            copyFieldToOutputStream(out, length, Pipe.byteBackingArray(meta, pipe), off, pipe.sizeOfBlobRing-off);
        }
    }

    private static void copyFieldToOutputStream(OutputStream out, int length, byte[] backing, int off, int lenFromOffsetToEnd)
            throws IOException {
        if (lenFromOffsetToEnd>=length) {
            //simple add bytes
            out.write(backing, off, length); 
        } else {                        
            //rolled over the end of the buffer
            out.write(backing, off, lenFromOffsetToEnd);
            out.write(backing, 0, length-lenFromOffsetToEnd);
        }
    }
    
    public static boolean readFieldFromInputStream(Pipe pipe, InputStream inputStream, final int byteCount) throws IOException {
        return buildFieldFromInputStream(pipe, inputStream, byteCount, Pipe.getWorkingBlobHeadPosition(pipe), Pipe.blobMask(pipe), Pipe.blob(pipe), pipe.sizeOfBlobRing);
    }

    private static boolean buildFieldFromInputStream(Pipe pipe, InputStream inputStream, final int byteCount, int startPosition, int byteMask, byte[] buffer, int sizeOfBlobRing) throws IOException {
        boolean result = copyFromInputStreamLoop(inputStream, byteCount, startPosition, byteMask, buffer, sizeOfBlobRing, 0);        
        Pipe.addBytePosAndLen(pipe, startPosition, byteCount);
        Pipe.addAndGetBytesWorkingHeadPosition(pipe, byteCount);
        assert(Pipe.validateVarLength(pipe, byteCount));
        return result;
    }

    private static boolean copyFromInputStreamLoop(InputStream inputStream, int remaining, int position, int byteMask, byte[] buffer, int sizeOfBlobRing, int size) throws IOException {
        while ( (remaining>0) && (size=safeRead(inputStream, position&byteMask, buffer, sizeOfBlobRing, remaining))>=0 ) { 
            if (size>0) {
                remaining -= size;                    
                position += size;
            } else {
                if (size<0) {
                	return false;
                }
            	Thread.yield();
                
            }
        }
        return true;
    }
    
    static int safeRead(InputStream inputStream, int position, byte[] buffer, int sizeOfBlobRing, int remaining) throws IOException {
        return inputStream.read(buffer, position, safeLength(sizeOfBlobRing, position, remaining)  );
    }
    
    static int safeRead(DataInput dataInput, int position, byte[] buffer, int sizeOfBlobRing, int remaining) throws IOException {
        int safeLength = safeLength(sizeOfBlobRing, position, remaining);
		dataInput.readFully(buffer, position, safeLength);
		return safeLength;
    }
    
    static int safeLength(int sizeOfBlobRing, int position, int remaining) {
        return ((position+remaining)<=sizeOfBlobRing) ? remaining : sizeOfBlobRing-position;
    }
    
    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobForWritingA(int originalBlobPosition, Pipe<S> output) {
        ByteBuffer target = output.wrappedBlobWritingRingA; //Get the blob array as a wrapped byte buffer     
        int writeToPos = originalBlobPosition & Pipe.blobMask(output); //Get the offset in the blob where we should write
        target.limit(target.capacity());
        target.position(writeToPos);   
        target.limit(Math.min(target.capacity(), writeToPos+output.maxVarLen)); //ensure we stop at end of wrap or max var length 
        return target;
    }

    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobForWritingB(int originalBlobPosition, Pipe<S> output) {
        ByteBuffer target = output.wrappedBlobWritingRingB; //Get the blob array as a wrapped byte buffer     
        int writeToPos = originalBlobPosition & Pipe.blobMask(output); //Get the offset in the blob where we should write
        target.position(0);   
        int endPos = writeToPos+output.maxVarLen;
    	if (endPos>output.sizeOfBlobRing) {
    		target.limit(output.blobMask & endPos);
    	} else {
    		target.limit(0);
    	}
        return target;
    }
      
    public static <S extends MessageSchema<S>> ByteBuffer[] wrappedWritingBuffers(Pipe<S> output) {
    	return wrappedWritingBuffers(Pipe.storeBlobWorkingHeadPosition(output),output);
    }
    
    public static <S extends MessageSchema<S>> ByteBuffer[] wrappedWritingBuffers(int originalBlobPosition, Pipe<S> output) {
    	int writeToPos = originalBlobPosition & Pipe.blobMask(output); //Get the offset in the blob where we should write
    	
    	int endPos = writeToPos+output.maxVarLen;
    	    	
    	assert(verifyHasRoomForWrite(output.maxVarLen, output));
    	    	
    	
    	ByteBuffer aBuf = output.wrappedBlobWritingRingA; //Get the blob array as a wrapped byte buffer     
		aBuf.limit(aBuf.capacity());
		aBuf.position(writeToPos);   
		aBuf.limit(Math.min(aBuf.capacity(), endPos ));
		
		ByteBuffer bBuf = output.wrappedBlobWritingRingB; //Get the blob array as a wrapped byte buffer     
		bBuf.position(0);   
		bBuf.limit(endPos>output.sizeOfBlobRing ? output.blobMask & endPos: 0);
		
		return output.wrappedWritingBuffers;
    }
 
    public static <S extends MessageSchema<S>> void moveBlobPointerAndRecordPosAndLength(int len, Pipe<S> output) {
    	moveBlobPointerAndRecordPosAndLength(Pipe.unstoreBlobWorkingHeadPosition(output), len, output);
    }
    
    public static <S extends MessageSchema<S>> void moveBlobPointerAndRecordPosAndLength(int originalBlobPosition, int len, Pipe<S> output) {
    	
    	assert(verifyHasRoomForWrite(len, output));    	
    	
    	//blob head position is moved forward
        Pipe.addAndGetBytesWorkingHeadPosition(output, len);
        //record the new start and length to the slab for this blob
        Pipe.addBytePosAndLen(output, originalBlobPosition, len);
    }

	private static <S extends MessageSchema<S>> boolean verifyHasRoomForWrite(int len, Pipe<S> output) {
				
		int h = getWorkingBlobHeadPosition(output)&output.blobMask;
    	int t = getBlobTailPosition(output)&output.blobMask;
    	int consumed;
    	if (h>=t) {
    		consumed = len+(h-t);
			assert(consumed<=output.blobMask) : "length too large for existing data, proposed addition "+len+" head "+h+" tail "+t+" "+output+" "+Pipe.contentRemaining(output);
    	} else {
    		consumed = len+h+(output.sizeOfBlobRing-t);
			assert(consumed<=output.blobMask) : "length is too large for existing data  "+len+" + t:"+t+" h:"+h+" max "+output.blobMask;
    	}
    	return (consumed<=output.blobMask);
	}


    @Deprecated
    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobRingA(Pipe<S> pipe, int meta, int len) {
        return wrappedBlobReadingRingA(pipe, meta, len);
    }
    
    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobReadingRingA(Pipe<S> pipe, int meta, int len) {
        ByteBuffer buffer;
        if (meta < 0) {
        	buffer = wrappedBlobConstBuffer(pipe);
        	int position = PipeReader.POS_CONST_MASK & meta;    
        	buffer.position(position);
        	buffer.limit(position+len);        	
        } else {
        	buffer = wrappedBlobRingA(pipe);
        	int position = pipe.blobMask & restorePosition(pipe,meta);
        	buffer.clear();
        	buffer.position(position);
        	//use the end of the buffer if the length runs past it.
        	buffer.limit(Math.min(pipe.sizeOfBlobRing, position+len));
        }
        return buffer;
    }
    
    @Deprecated
    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobRingB(Pipe<S> pipe, int meta, int len) {
        return wrappedBlobReadingRingB(pipe,meta,len);
    }

    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobReadingRingB(Pipe<S> pipe, int meta, int len) {
        ByteBuffer buffer;
        if (meta < 0) {
        	//always zero because constant array never wraps
        	buffer = wrappedBlobConstBuffer(pipe);
        	buffer.position(0);
        	buffer.limit(0);
        } else {
        	buffer = wrappedBlobRingB(pipe);
        	int position = pipe.blobMask & restorePosition(pipe,meta);
        	buffer.clear();
            //position is zero
        	int endPos = position+len;
        	if (endPos>pipe.sizeOfBlobRing) {
        		buffer.limit(pipe.blobMask & endPos);
        	} else {
        		buffer.limit(0);
        	}
        }		
    	return buffer;
    }

    public static <S extends MessageSchema<S>> ByteBuffer[] wrappedReadingBuffers(Pipe<S> pipe, int meta, int len) {
    	if (meta >= 0) {
    		wrappedReadingBuffersRing(pipe, meta, len);
		} else {
			wrappedReadingBufffersConst(pipe, meta, len);
		}
		return pipe.wrappedReadingBuffers;
    }

	private static <S extends MessageSchema<S>> void wrappedReadingBuffersRing(Pipe<S> pipe, int meta, int len) {
		
		final int position = pipe.blobMask&bytePosition(meta,pipe,len);//MUST call this one which creates side effect of assuming this data is consumed
		final int endPos = position+len;
		
	    assert(Pipe.validatePipeBlobHasDataToRead(pipe, position, len));
		
		ByteBuffer aBuf = wrappedBlobRingA(pipe);
		aBuf.clear();
		aBuf.position(position);
		//use the end of the buffer if the length runs past it.
		aBuf.limit(Math.min(pipe.sizeOfBlobRing, endPos));
		
		ByteBuffer bBuf = wrappedBlobRingB(pipe);
		bBuf.clear();
		bBuf.limit(endPos > pipe.sizeOfBlobRing ? pipe.blobMask & endPos : 0 ); 
				
	}

	private static <S extends MessageSchema<S>> void wrappedReadingBufffersConst(Pipe<S> pipe, int meta, int len) {
		
		ByteBuffer aBuf = wrappedBlobConstBuffer(pipe);
		int position = PipeReader.POS_CONST_MASK & meta;    
		aBuf.position(position);
		aBuf.limit(position+len);        	

		//always zero because constant array never wraps
		ByteBuffer bBuf = wrappedBlobConstBuffer(pipe);
		bBuf.position(0);
		bBuf.limit(0);
	}
    

    public static int convertToUTF8(final char[] charSeq, final int charSeqOff, final int charSeqLength, final byte[] targetBuf, final int targetIdx, final int targetMask) {
    	
    	int target = targetIdx;				
        int c = 0;
        while (c < charSeqLength) {
        	target = encodeSingleChar((int) charSeq[charSeqOff+c++], targetBuf, targetMask, target);
        }
        //NOTE: the above loop will keep looping around the target buffer until done and will never cause an array out of bounds.
        //      the length returned however will be larger than targetMask, this should be treated as an error.
        return target-targetIdx;//length;
    }

    public static int convertToUTF8(final CharSequence charSeq, final int charSeqOff, final int charSeqLength, final byte[] targetBuf, final int targetIdx, final int targetMask) {
        /**
         * 
         * Converts CharSequence (base class of String) into UTF-8 encoded bytes and writes those bytes to an array.
         * The write loops around the end using the targetMask so the returned length must be checked after the call
         * to determine if and overflow occurred. 
         * 
         * Due to the variable nature of converting chars into bytes there is not easy way to know before walking how
         * many bytes will be needed.  To prevent any overflow ensure that you have 6*lengthOfCharSequence bytes available.
         * 
         */
    	
    	int target = targetIdx;				
        int c = 0;
        while (c < charSeqLength) {
        	target = encodeSingleChar((int) charSeq.charAt(charSeqOff+c++), targetBuf, targetMask, target);
        }
        //NOTE: the above loop will keep looping around the target buffer until done and will never cause an array out of bounds.
        //      the length returned however will be larger than targetMask, this should be treated as an error.
        return target-targetIdx;//length;
    }

    public static <S extends MessageSchema<S>> void appendFragment(Pipe<S> input, Appendable target, int cursor) {
        try {

            FieldReferenceOffsetManager from = from(input);
            int fields = from.fragScriptSize[cursor];
            assert (cursor<from.tokensLen-1);//there are no single token messages so there is no room at the last position.


            int dataSize = from.fragDataSize[cursor];
            String msgName = from.fieldNameScript[cursor];
            long msgId = from.fieldIdScript[cursor];

            target.append(" cursor:"+cursor+
                           " fields: "+fields+" "+String.valueOf(msgName)+
                           " id: "+msgId).append("\n");

            if (0==fields && cursor==from.tokensLen-1) { //this is an odd case and should not happen
                //TODO: AA length is too long and we need to detect cursor out of bounds!
                System.err.println("total tokens:"+from.tokens.length);//Arrays.toString(from.fieldNameScript));
                throw new RuntimeException("unable to convert fragment to text");
            }


            int i = 0;
            while (i<fields) {
                final int p = i+cursor;
                String name = from.fieldNameScript[p];
                long id = from.fieldIdScript[p];

                int token = from.tokens[p];
                int type = TokenBuilder.extractType(token);

                //fields not message name
                String value = "";
                if (i>0 || !input.ringWalker.isNewMessage) {
                    int pos = from.fragDataSize[i+cursor];
                    //create string values of each field so we can see them easily
                    switch (type) {
                        case TypeMask.Group:

                            int oper = TokenBuilder.extractOper(token);
                            boolean open = (0==(OperatorMask.Group_Bit_Close&oper));
                            value = "open:"+open+" pos:"+p;

                            break;
                        case TypeMask.GroupLength:
                            int len = readInt(slab(input), input.mask, pos+tailPosition(input));
                            value = Integer.toHexString(len)+"("+len+")";
                            break;
                        case TypeMask.IntegerSigned:
                        case TypeMask.IntegerUnsigned:
                        case TypeMask.IntegerSignedOptional:
                        case TypeMask.IntegerUnsignedOptional:
                            int readInt = readInt(slab(input), input.mask, pos+tailPosition(input));
                            value = Integer.toHexString(readInt)+"("+readInt+")";
                            break;
                        case TypeMask.LongSigned:
                        case TypeMask.LongUnsigned:
                        case TypeMask.LongSignedOptional:
                        case TypeMask.LongUnsignedOptional:
                            long readLong = readLong(slab(input), input.slabMask, pos+tailPosition(input));
                            value = Long.toHexString(readLong)+"("+readLong+")";
                            break;
                        case TypeMask.Decimal:
                        case TypeMask.DecimalOptional:

                            int exp = readInt(slab(input), input.slabMask, pos+tailPosition(input));
                            long mantissa = readLong(slab(input), input.slabMask, pos+tailPosition(input)+1);
                            value = exp+" "+mantissa;

                            break;
                        case TypeMask.TextASCII:
                        case TypeMask.TextASCIIOptional:
                            {
                                int meta = readInt(slab(input), input.slabMask, pos+tailPosition(input));
                                int length = readInt(slab(input), input.slabMask, pos+tailPosition(input)+1);
                                readASCII(input, target, meta, length);
                                value = meta+" len:"+length;
                                // value = target.toString();
                            }
                            break;
                        case TypeMask.TextUTF8:
                        case TypeMask.TextUTF8Optional:

                            {
                                int meta = readInt(slab(input), input.slabMask, pos+tailPosition(input));
                                int length = readInt(slab(input), input.slabMask, pos+tailPosition(input)+1);
                                readUTF8(input, target, meta, length);
                                value = meta+" len:"+length;
                               // value = target.toString();
                            }
                            break;
                        case TypeMask.ByteVector:
                        case TypeMask.ByteVectorOptional:
                            {
                                int meta = readInt(slab(input), input.slabMask, pos+tailPosition(input));
                                int length = readInt(slab(input), input.slabMask, pos+tailPosition(input)+1);
                                value = meta+" len:"+length;

                            }
                            break;
                        default: target.append("unknown ").append("\n");

                    }


                    value += (" "+TypeMask.toString(type)+" "+pos);
                }

                target.append("   "+name+":"+id+"  "+value).append("\n");

                //TWEET  x+t+"xxx" is a bad idea.


                if (TypeMask.Decimal==type || TypeMask.DecimalOptional==type) {
                    i++;//skip second slot for decimals
                }

                i++;
            }
        } catch (IOException ioe) {
            PipeReader.log.error("Unable to build text for fragment.",ioe);
            throw new RuntimeException(ioe);
        }
    }

    public static <S extends MessageSchema<S>> ByteBuffer readBytes(Pipe<S> pipe, ByteBuffer target, int meta, int len) {
		if (meta >= 0) {
			return readBytesRing(pipe,len,target,restorePosition(pipe,meta));
	    } else {
	    	return readBytesConst(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
	    }
	}
    
    public static <S extends MessageSchema<S>> DataOutputBlobWriter<?> readBytes(Pipe<S> pipe, DataOutputBlobWriter<?> target, int meta, int len) {
		if (meta >= 0) {
			return readBytesRing(pipe,len,target,restorePosition(pipe,meta));
	    } else {
	    	return readBytesConst(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
	    }
	}

    public static <S extends MessageSchema<S>> void readBytes(Pipe<S> pipe, byte[] target, int targetIdx, int targetMask, int meta, int len) {
		if (meta >= 0) {
			copyBytesFromToRing(pipe.blobRing,restorePosition(pipe,meta),pipe.blobMask,target,targetIdx,targetMask,len);
	    } else {
	    	//NOTE: constByteBuffer does not wrap so we do not need the mask
	    	copyBytesFromToRing(pipe.blobConstBuffer, PipeReader.POS_CONST_MASK & meta, 0xFFFFFFFF, target, targetIdx, targetMask, len);
	    }
	}

	private static <S extends MessageSchema<S>> ByteBuffer readBytesRing(Pipe<S> pipe, int len, ByteBuffer target, int pos) {
		int mask = pipe.blobMask;
		byte[] buffer = pipe.blobRing;

        int tStart = pos & mask;
        int len1 = 1+mask - tStart;

		if (len1>=len) {
			target.put(buffer, mask&pos, len);
		} else {
			target.put(buffer, mask&pos, len1);
			target.put(buffer, 0, len-len1);
		}

	    return target;
	}
	
	private static <S extends MessageSchema<S>> DataOutputBlobWriter<?> readBytesRing(Pipe<S> pipe, int len, DataOutputBlobWriter<?> target, int pos) {
				
		DataOutputBlobWriter.write(target, pipe.blobRing, pos, len, pipe.blobMask);
		
		//could use this code to make something similar for writing to  DataOutput or output stream?
//		int mask = pipe.blobMask;
//		byte[] buffer = pipe.blobRing;
//        int len1 = 1+mask - (pos & mask);
//
//		if (len1>=len) {
//			target.write(buffer, mask&pos, len);
//		} else {
//			target.write(buffer, mask&pos, len1);
//			target.write(buffer, 0, len-len1);
//		}

	    return target;
	}

	private static <S extends MessageSchema<S>> ByteBuffer readBytesConst(Pipe<S> pipe, int len, ByteBuffer target, int pos) {
	    	target.put(pipe.blobConstBuffer, pos, len);
	        return target;
	    }

	private static <S extends MessageSchema<S>> DataOutputBlobWriter<?> readBytesConst(Pipe<S> pipe, int len, DataOutputBlobWriter<?> target, int pos) {
    	target.write(pipe.blobConstBuffer, pos, len);
        return target;
    }
	
	public static <S extends MessageSchema<S>, A extends Appendable> A readASCII(Pipe<S> pipe, A target, int meta, int len) {
		if (meta < 0) {//NOTE: only useses const for const or default, may be able to optimize away this conditional.
	        return readASCIIConst(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
	    } else {
	        return readASCIIRing(pipe,len,target,restorePosition(pipe, meta));
	    }
	}
	
   public static <S extends MessageSchema<S>, A extends Appendable> A readOptionalASCII(Pipe<S> pipe, A target, int meta, int len) {
        if (len<0) {
            return null;
        }
        if (meta < 0) {//NOTE: only useses const for const or default, may be able to optimize away this conditional.
            return readASCIIConst(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
        } else {
            return readASCIIRing(pipe,len,target,restorePosition(pipe, meta));
        }
    }

   
   public static <S extends MessageSchema<S>> void skipNextFragment(Pipe<S> pipe) {
		   
	   skipNextFragment(pipe, Pipe.takeMsgIdx(pipe));
	   
   }

	public static <S extends MessageSchema<S>> void skipNextFragment(Pipe<S> pipe, int msgIdx) {
		   long pos = Pipe.getWorkingTailPosition(pipe);
		   int msgSize = Pipe.sizeOf(pipe, msgIdx);
		   int idx = (int)(pos+msgSize-2);
		   int msgBytesConsumed = Pipe.slab(pipe)[ Pipe.slabMask(pipe) & idx ]; 

		   //position for the bytes consumed is stepped over and we have already moved forward by size of messageIdx header so substract 2.	   
		   pipe.slabRingTail.workingTailPos.value += (msgSize-2); 
		   pipe.blobRingTail.byteWorkingTailPos.value =  pipe.blobMask & (msgBytesConsumed + pipe.blobRingTail.byteWorkingTailPos.value);
		   
		   Pipe.confirmLowLevelRead(pipe, msgSize);
		   Pipe.releaseReadLock(pipe);
	}
		   
   
	public static <S extends MessageSchema<S>> boolean isEqual(Pipe<S> pipe, CharSequence charSeq, int meta, int len) {
		if (len!=charSeq.length()) {
			return false;
		}
		if (meta < 0) {

			int pos = PipeReader.POS_CONST_MASK & meta;

	    	byte[] buffer = pipe.blobConstBuffer;
	    	assert(null!=buffer) : "If constants are used the constByteBuffer was not initialized. Otherwise corruption in the stream has been discovered";
	    	while (--len >= 0) {
	    		if (charSeq.charAt(len)!=buffer[pos+len]) {
	    			return false;
	    		}
	        }

		} else {

			byte[] buffer = pipe.blobRing;
			int mask = pipe.blobMask;
			int pos = restorePosition(pipe, meta);

	        while (--len >= 0) {
	    		if (charSeq.charAt(len)!=buffer[mask&(pos+len)]) {
	    			return false;
	    		}
	        }

		}

		return true;
	}

	   public static <S extends MessageSchema<S>> boolean isEqual(Pipe<S> pipe, byte[] expected, int expectedPos, int meta, int len) {
	        if (len>(expected.length-expectedPos)) {
	            return false;
	        }
	        if (meta < 0) {

	            int pos = PipeReader.POS_CONST_MASK & meta;

	            byte[] buffer = pipe.blobConstBuffer;
	            assert(null!=buffer) : "If constants are used the constByteBuffer was not initialized. Otherwise corruption in the stream has been discovered";
	            
	            while (--len >= 0) {
	                if (expected[expectedPos+len]!=buffer[pos+len]) {
	                    return false;
	                }
	            }

	        } else {

	            byte[] buffer = pipe.blobRing;
	            int mask = pipe.blobMask;
	            int pos = restorePosition(pipe, meta);

	            while (--len >= 0) {
	                if (expected[expectedPos+len]!=buffer[mask&(pos+len)]) {
	                    return false;
	                }
	            }

	        }

	        return true;
	    }
	
	   public static boolean isEqual(byte[] aBack, int aPos, int aMask, 
			                         byte[] bBack, int bPos, int bMask, int len) {

		   while (--len>=0) {
			   byte a = aBack[(aPos+len)&aMask];
			   byte b = bBack[(bPos+len)&bMask];
			   if (a!=b) {
				   return false;
			   }
		   }
		   return true;
		   
	   }

	   
	private static <S extends MessageSchema<S>,  A extends Appendable> A readASCIIRing(Pipe<S> pipe, int len, A target, int pos) {
		byte[] buffer = pipe.blobRing;
		int mask = pipe.blobMask;

	    try {
	        while (--len >= 0) {
	            target.append((char)buffer[mask & pos++]);
	        }
	    } catch (IOException e) {
	       throw new RuntimeException(e);
	    }
	    return target;
	}

	private static <S extends MessageSchema<S>, A extends Appendable> A readASCIIConst(Pipe<S> pipe, int len, A target, int pos) {
	    try {
	    	byte[] buffer = pipe.blobConstBuffer;
	    	assert(null!=buffer) : "If constants are used the constByteBuffer was not initialized. Otherwise corruption in the stream has been discovered";
	    	while (--len >= 0) {
	            target.append((char)buffer[pos++]);
	        }
	    } catch (IOException e) {
	       throw new RuntimeException(e);
	    }
	    return target;
	}

	

	public static <S extends MessageSchema<S>, A extends Appendable> A readUTF8(Pipe<S> pipe, A target, int meta, int len) { 
    		if (meta < 0) {//NOTE: only useses const for const or default, may be able to optimize away this conditional.
    	        return (A) readUTF8Const(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
    	    } else {
    	        return (A) readUTF8Ring(pipe,len,target,restorePosition(pipe,meta));
    	    }
	}
	
	   public static <S extends MessageSchema<S>> Appendable readOptionalUTF8(Pipe<S> pipe, Appendable target, int meta, int len) {
	       
    	     if (len<0) {
    	         return null;
    	     }
	        if (meta < 0) {//NOTE: only useses const for const or default, may be able to optimize away this conditional.
	            return readUTF8Const(pipe,len,target,PipeReader.POS_CONST_MASK & meta);
	        } else {
	            return readUTF8Ring(pipe,len,target,restorePosition(pipe,meta));
	        }
	        
	    }

	private static <S extends MessageSchema<S>> Appendable readUTF8Const(Pipe<S> pipe, int bytesLen, Appendable target, int ringPos) {
		  try{
			  long charAndPos = ((long)ringPos)<<32;
			  long limit = ((long)ringPos+bytesLen)<<32;

			  while (charAndPos<limit) {
			      charAndPos = decodeUTF8Fast(pipe.blobConstBuffer, charAndPos, 0xFFFFFFFF); //constants do not wrap
			      target.append((char)charAndPos);
			  }
		  } catch (IOException e) {
			  throw new RuntimeException(e);
		  }
		  return target;
	}

	private static <S extends MessageSchema<S>> Appendable readUTF8Ring(Pipe<S> pipe, int bytesLen, Appendable target, int ringPos) {
		  try{
			  long charAndPos = ((long)ringPos)<<32;
			  long limit = ((long)ringPos+bytesLen)<<32;

			  while (charAndPos<limit) {
			      charAndPos = decodeUTF8Fast(pipe.blobRing, charAndPos, pipe.byteMask);
			      target.append((char)charAndPos);
			  }
		  } catch (IOException e) {
			  throw new RuntimeException(e);
		  }
		  return target;
	}

	public static <S extends MessageSchema<S>> void addDecimalAsASCII(int readDecimalExponent,	long readDecimalMantissa, Pipe<S> outputRing) {
		long ones = (long)(readDecimalMantissa*PipeReader.powdi[64 + readDecimalExponent]);
		validateVarLength(outputRing, 21);
		int max = 21 + outputRing.blobRingHead.byteWorkingHeadPos.value;
		int len = leftConvertLongToASCII(outputRing, ones, max);
		outputRing.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(len + outputRing.blobRingHead.byteWorkingHeadPos.value);

		copyASCIIToBytes(".", outputRing);

		long frac = Math.abs(readDecimalMantissa - (long)(ones/PipeReader.powdi[64 + readDecimalExponent]));

		validateVarLength(outputRing, 21);
		int max1 = 21 + outputRing.blobRingHead.byteWorkingHeadPos.value;
		int len1 = leftConvertLongWithLeadingZerosToASCII(outputRing, readDecimalExponent, frac, max1);
		outputRing.blobRingHead.byteWorkingHeadPos.value = Pipe.BYTES_WRAP_MASK&(len1 + outputRing.blobRingHead.byteWorkingHeadPos.value);

		//may require trailing zeros
		while (len1<readDecimalExponent) {
			copyASCIIToBytes("0",outputRing);
			len1++;
		}
	}

	public static int safeBlobPosAdd(int pos, long value) {
	    return (int)(Pipe.BYTES_WRAP_MASK&(pos+value));
	}
	

	/**
     * All bytes even those not yet committed.
     *
     * @param ringBuffer
     */
	public static <S extends MessageSchema<S>> int bytesOfContent(Pipe<S> ringBuffer) {
		int dif = (ringBuffer.byteMask&ringBuffer.blobRingHead.byteWorkingHeadPos.value) - (ringBuffer.byteMask&PaddedInt.get(ringBuffer.blobRingTail.bytesTailPos));
		return ((dif>>31)<<ringBuffer.bitsOfBlogRing)+dif;
	}

	public static <S extends MessageSchema<S>> void validateBatchSize(Pipe<S> pipe, int size) {
		int maxBatch = computeMaxBatchSize(pipe);
		if (size>maxBatch) {
			throw new UnsupportedOperationException("For the configured pipe buffer the batch size can be no larger than "+maxBatch);
		}
	}

	public static <S extends MessageSchema<S>> int computeMaxBatchSize(Pipe<S> rb) {
		return computeMaxBatchSize(rb,2);//default mustFit of 2
	}

	public static <S extends MessageSchema<S>> int computeMaxBatchSize(Pipe<S> pipe, int mustFit) {
		assert(mustFit>=1);
		int maxBatchFromBytes = pipe.maxVarLen==0?Integer.MAX_VALUE:(pipe.sizeOfBlobRing/pipe.maxVarLen)/mustFit;
		int maxBatchFromPrimary = (pipe.sizeOfSlabRing/FieldReferenceOffsetManager.maxFragmentSize(from(pipe)))/mustFit;
		return Math.min(maxBatchFromBytes, maxBatchFromPrimary);
	}

	public static <S extends MessageSchema<S>> boolean isEndOfPipe(Pipe<S> pipe, long tailPosition) {
		return tailPosition>=pipe.knownPositionOfEOF;
	}
	
	public static <S extends MessageSchema<S>> void publishEOF(Pipe<S> pipe) {

		assert(pipe.slabRingTail.tailPos.get()+pipe.sizeOfSlabRing>=pipe.slabRingHead.headPos.get()+Pipe.EOF_SIZE) : "Must block first to ensure we have 2 spots for the EOF marker";

		PaddedInt.set(pipe.blobRingHead.bytesHeadPos,pipe.blobRingHead.byteWorkingHeadPos.value);
		pipe.knownPositionOfEOF = (int)pipe.slabRingHead.workingHeadPos.value +  from(pipe).templateOffset;
		pipe.slabRing[pipe.slabMask & (int)pipe.knownPositionOfEOF]    = -1;
		pipe.slabRing[pipe.slabMask & ((int)pipe.knownPositionOfEOF+1)] = 0;

		pipe.slabRingHead.headPos.lazySet(pipe.slabRingHead.workingHeadPos.value = pipe.slabRingHead.workingHeadPos.value + Pipe.EOF_SIZE);

	}

	public static void copyBytesFromToRing(byte[] source, int sourceloc, int sourceMask, byte[] target, int targetloc, int targetMask, int length) {
		copyBytesFromToRingMasked(source, sourceloc & sourceMask, (sourceloc + length) & sourceMask, target, targetloc & targetMask, (targetloc + length) & targetMask,	length);
	}

	public static void copyIntsFromToRing(int[] source, int sourceloc, int sourceMask, int[] target, int targetloc, int targetMask, int length) {
		copyIntsFromToRingMasked(source, sourceloc & sourceMask, (sourceloc + length) & sourceMask, target, targetloc & targetMask, (targetloc + length) & targetMask, length);
	}

	public static void copyBytesFromArrayToRing(byte[] source, int sourceloc, byte[] target, int targetloc, int targetMask, int length) {
		copyBytesFromToRingMasked(source, sourceloc, (sourceloc + length), target, targetloc & targetMask, (targetloc + length) & targetMask,	length);
	}

	private static void copyBytesFromToRingMasked(byte[] source,
			final int rStart, final int rStop, byte[] target, final int tStart,
			final int tStop, int length) {
		if (tStop > tStart) {
			//do not accept the equals case because this can not work with data the same length as as the buffer
			doubleMaskTargetDoesNotWrap(source, rStart, rStop, target, tStart, length);
		} else {
			doubleMaskTargetWraps(source, rStart, rStop, target, tStart, tStop,	length);
		}
	}


	private static void copyIntsFromToRingMasked(int[] source,
			final int rStart, final int rStop, int[] target, final int tStart,
			final int tStop, int length) {
		if (tStop > tStart) {
			doubleMaskTargetDoesNotWrap(source, rStart, rStop, target, tStart, length);
		} else {
			doubleMaskTargetWraps(source, rStart, rStop, target, tStart, tStop,	length);
		}
	}

	private static void doubleMaskTargetDoesNotWrap(byte[] source,
			final int srcStart, final int srcStop, byte[] target, final int trgStart,	int length) {
		if (srcStop >= srcStart) {
			//the source and target do not wrap
			System.arraycopy(source, srcStart, target, trgStart, length);
		} else {
			//the source is wrapping but not the target
			System.arraycopy(source, srcStart, target, trgStart, length-srcStop);
			System.arraycopy(source, 0, target, trgStart + length - srcStop, srcStop);
		}
	}

	private static void doubleMaskTargetDoesNotWrap(int[] source,
			final int rStart, final int rStop, int[] target, final int tStart,
			int length) {
		if (rStop > rStart) {
			//the source and target do not wrap
			System.arraycopy(source, rStart, target, tStart, length);
		} else {
			//the source is wrapping but not the target
			System.arraycopy(source, rStart, target, tStart, length-rStop);
			System.arraycopy(source, 0, target, tStart + length - rStop, rStop);
		}
	}

	private static void doubleMaskTargetWraps(byte[] source, final int rStart,
			final int rStop, byte[] target, final int tStart, final int tStop,
			int length) {
		if (rStop > rStart) {
//				//the source does not wrap but the target does
//				// done as two copies
		    System.arraycopy(source, rStart, target, tStart, length-tStop);
		    System.arraycopy(source, rStart + length - tStop, target, 0, tStop);
		} else {
		    if (length>0) {
				//both the target and the source wrap
		    	doubleMaskDoubleWrap(source, target, length, tStart, rStart, length-tStop, length-rStop);
			}
		}
	}

	private static void doubleMaskTargetWraps(int[] source, final int rStart,
			final int rStop, int[] target, final int tStart, final int tStop,
			int length) {
		if (rStop > rStart) {
//				//the source does not wrap but the target does
//				// done as two copies
		    System.arraycopy(source, rStart, target, tStart, length-tStop);
		    System.arraycopy(source, rStart + length - tStop, target, 0, tStop);
		} else {
		    if (length>0) {
				//both the target and the source wrap
		    	doubleMaskDoubleWrap(source, target, length, tStart, rStart, length-tStop, length-rStop);
			}
		}
	}

	private static void doubleMaskDoubleWrap(byte[] source, byte[] target,
			int length, final int tStart, final int rStart, int targFirstLen,
			int srcFirstLen) {
		
		if (srcFirstLen<targFirstLen) {
			//split on src first
			System.arraycopy(source, rStart, target, tStart, srcFirstLen);
			System.arraycopy(source, 0, target, tStart+srcFirstLen, targFirstLen - srcFirstLen);
			System.arraycopy(source, targFirstLen - srcFirstLen, target, 0, length - targFirstLen);
		} else {
			//split on targ first
			System.arraycopy(source, rStart, target, tStart, targFirstLen);
			System.arraycopy(source, rStart + targFirstLen, target, 0, srcFirstLen - targFirstLen);
			System.arraycopy(source, 0, target, srcFirstLen - targFirstLen, length - srcFirstLen);
		}
		
	}

	private static void doubleMaskDoubleWrap(int[] source, int[] target,
			int length, final int tStart, final int rStart, int targFirstLen,
			int srcFirstLen) {
		if (srcFirstLen<targFirstLen) {
			//split on src first
			System.arraycopy(source, rStart, target, tStart, srcFirstLen);
			System.arraycopy(source, 0, target, tStart+srcFirstLen, targFirstLen - srcFirstLen);
			System.arraycopy(source, targFirstLen - srcFirstLen, target, 0, length - targFirstLen);
		} else {
			//split on targ first
			System.arraycopy(source, rStart, target, tStart, targFirstLen);
			System.arraycopy(source, rStart + targFirstLen, target, 0, srcFirstLen - targFirstLen);
			System.arraycopy(source, 0, target, srcFirstLen - targFirstLen, length - srcFirstLen);
		}
	}

	@Deprecated //use the Appendables methods
	public static <S extends MessageSchema<S>> int leftConvertIntToASCII(Pipe<S> pipe, int value, int idx) {
		//max places is value for -2B therefore its 11 places so we start out that far and work backwards.
		//this will leave a gap but that is not a problem.
		byte[] target = pipe.blobRing;
		int tmp = Math.abs(value);
		int max = idx;
		do {
			//do not touch these 2 lines they make use of secret behavior in hot spot that does a single divide.
			int t = tmp/10;
			int r = tmp%10;
			target[pipe.byteMask&--idx] = (byte)('0'+r);
			tmp = t;
		} while (0!=tmp);
		target[pipe.byteMask& (idx-1)] = (byte)'-';
		//to make it positive we jump over the sign.
		idx -= (1&(value>>31));

		//shift it down to the head
		int length = max-idx;
		if (idx!=pipe.blobRingHead.byteWorkingHeadPos.value) {
			int s = 0;
			while (s<length) {
				target[pipe.byteMask & (s+pipe.blobRingHead.byteWorkingHeadPos.value)] = target[pipe.byteMask & (s+idx)];
				s++;
			}
		}
		return length;
	}

	@Deprecated //use the Appendables methods
	public static <S extends MessageSchema<S>> int leftConvertLongToASCII(Pipe<S> pipe, long value,	int idx) {
		//max places is value for -2B therefore its 11 places so we start out that far and work backwards.
		//this will leave a gap but that is not a problem.
		byte[] target = pipe.blobRing;
		long tmp = Math.abs(value);
		int max = idx;
		do {
			//do not touch these 2 lines they make use of secret behavior in hot spot that does a single divide.
			long t = tmp/10;
			long r = tmp%10;
			target[pipe.byteMask&--idx] = (byte)('0'+r);
			tmp = t;
		} while (0!=tmp);
		target[pipe.byteMask& (idx-1)] = (byte)'-';
		//to make it positive we jump over the sign.
		idx -= (1&(value>>63));

		int length = max-idx;
		//shift it down to the head
		if (idx!=pipe.blobRingHead.byteWorkingHeadPos.value) {
			int s = 0;
			while (s<length) {
				target[pipe.byteMask & (s+pipe.blobRingHead.byteWorkingHeadPos.value)] = target[pipe.byteMask & (s+idx)];
				s++;
			}
		}
		return length;
	}

   public static <S extends MessageSchema<S>> int leftConvertLongWithLeadingZerosToASCII(Pipe<S> pipe, int chars, long value, int idx) {
        //max places is value for -2B therefore its 11 places so we start out that far and work backwards.
        //this will leave a gap but that is not a problem.
        byte[] target = pipe.blobRing;
        long tmp = Math.abs(value);
        int max = idx;

        do {
            //do not touch these 2 lines they make use of secret behavior in hot spot that does a single divide.
            long t = tmp/10;
            long r = tmp%10;
            target[pipe.byteMask&--idx] = (byte)('0'+r);
            tmp = t;
            chars--;
        } while (0!=tmp);
        while(--chars>=0) {
            target[pipe.byteMask&--idx] = '0';
        }

        target[pipe.byteMask& (idx-1)] = (byte)'-';
        //to make it positive we jump over the sign.
        idx -= (1&(value>>63));

        int length = max-idx;
        //shift it down to the head
        if (idx!=pipe.blobRingHead.byteWorkingHeadPos.value) {
            int s = 0;
            while (s<length) {
                target[pipe.byteMask & (s+pipe.blobRingHead.byteWorkingHeadPos.value)] = target[pipe.byteMask & (s+idx)];
                s++;
            }
        }
        return length;
    }

	public static int readInt(int[] buffer, int mask, long index) {
		return buffer[mask & (int)(index)];
	}

	/**
	 * Read and return the int value at this position and clear the value with the provided clearValue.
	 * This ensures no future calls will be able to read the value once this is done.
	 *
	 * This is primarily needed for secure data xfers when the re-use of a ring buffer may 'leak' old values.
	 * It is also useful for setting flags in conjuction with the replay feature.
	 *
	 * @param buffer
	 * @param mask
	 * @param index
	 * @param clearValue
	 */
	public static int readIntSecure(int[] buffer, int mask, long index, int clearValue) {
	        int idx = mask & (int)(index);
            int result =  buffer[idx];
            buffer[idx] = clearValue;
            return result;
	}

	public static long readLong(int[] buffer, int mask, long index) {
		return (((long) buffer[mask & (int)index]) << 32) | (((long) buffer[mask & (int)(index + 1)]) & 0xFFFFFFFFl);
	}

	/**
	   * Convert bytes into chars using UTF-8.
	   *
	   *  High 32   BytePosition
	   *  Low  32   Char (caller can cast response to char to get the decoded value)
	   *
	   */
	  public static long decodeUTF8Fast(byte[] source, long posAndChar, int mask) { //pass in long of last position?

		  // 7  //high bit zero all others its 1
		  // 5 6
		  // 4 6 6
		  // 3 6 6 6
		  // 2 6 6 6 6
		  // 1 6 6 6 6 6

	    int sourcePos = (int)(posAndChar >> 32);

	    byte b;
	    if ((b = source[mask&sourcePos++]) >= 0) {
	        // code point 7
	        return (((long)sourcePos)<<32) | (long)b; //1 byte result of 7 bits with high zero
	    }

	    int result;
	    if (((byte) (0xFF & (b << 2))) >= 0) {
	        if ((b & 0x40) == 0) {
	            ++sourcePos;
	            return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	        }
	        // code point 11
	        result = (b & 0x1F); //5 bits
	    } else {
	        if (((byte) (0xFF & (b << 3))) >= 0) {
	            // code point 16
	            result = (b & 0x0F); //4 bits
	        } else {
	            if (((byte) (0xFF & (b << 4))) >= 0) {
	                // code point 21
	                result = (b & 0x07); //3 bits
	            } else {
	                if (((byte) (0xFF & (b << 5))) >= 0) {
	                    // code point 26
	                    result = (b & 0x03); // 2 bits
	                } else {
	                    if (((byte) (0xFF & (b << 6))) >= 0) {
	                        // code point 31
	                        result = (b & 0x01); // 1 bit
	                    } else {
	                        // the high bit should never be set
	                        sourcePos += 5;
	                        return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	                    }

	                    if ((source[mask&sourcePos] & 0xC0) != 0x80) {
	                        sourcePos += 5;
	                        return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	                    }
	                    result = (result << 6) | (int)(source[mask&sourcePos++] & 0x3F);
	                }
	                if ((source[mask&sourcePos] & 0xC0) != 0x80) {
	                    sourcePos += 4;
	                    return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	                }
	                result = (result << 6) | (int)(source[mask&sourcePos++] & 0x3F);
	            }
	            if ((source[mask&sourcePos] & 0xC0) != 0x80) {
	                sourcePos += 3;
	                return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	            }
	            result = (result << 6) | (int)(source[mask&sourcePos++] & 0x3F);
	        }
	        if ((source[mask&sourcePos] & 0xC0) != 0x80) {
	            sourcePos += 2;
	            return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	        }
	        result = (result << 6) | (int)(source[mask&sourcePos++] & 0x3F);
	    }
	    if ((source[mask&sourcePos] & 0xC0) != 0x80) {
	       log.error("Invalid encoding, low byte must have bits of 10xxxxxx but we find {}. conclusion: this data was not UTF8 encoded.",Integer.toBinaryString(source[mask&sourcePos]) );//,new Exception("Check for pipe corruption"));
	       sourcePos += 1;
	       return (((long)sourcePos)<<32) | 0xFFFD; // Bad data replacement char
	    }
	    long chr = ((result << 6) | (int)(source[mask&sourcePos++] & 0x3F)); //6 bits
	    return (((long)sourcePos)<<32) | chr;
	  }

	public static <S extends MessageSchema<S>> int copyASCIIToBytes(CharSequence source, Pipe<S> rbRingBuffer) {
		return copyASCIIToBytes(source, 0, source.length(), rbRingBuffer);
	}

	public static <S extends MessageSchema<S>> void addASCII(CharSequence source, Pipe<S> rb) {
	    addASCII(source, 0, null==source ? -1 : source.length(), rb);
	}

	public static <S extends MessageSchema<S>> void addASCII(CharSequence source, int sourceIdx, int sourceCharCount, Pipe<S> rb) {
		addBytePosAndLen(rb, copyASCIIToBytes(source, sourceIdx, sourceCharCount, rb), sourceCharCount);
	}

	public static <S extends MessageSchema<S>> void addASCII(char[] source, int sourceIdx, int sourceCharCount, Pipe<S> rb) {
		addBytePosAndLen(rb, copyASCIIToBytes(source, sourceIdx, sourceCharCount, rb), sourceCharCount);
	}

	public static <S extends MessageSchema<S>> int copyASCIIToBytes(CharSequence source, int sourceIdx, final int sourceLen, Pipe<S> rbRingBuffer) {
		final int p = rbRingBuffer.blobRingHead.byteWorkingHeadPos.value;
		//TODO: revisit this not sure this conditional is required
	    if (sourceLen > 0) {
	    	int tStart = p & rbRingBuffer.byteMask;
	        copyASCIIToBytes2(source, sourceIdx, sourceLen, rbRingBuffer, p, rbRingBuffer.blobRing, tStart, 1+rbRingBuffer.byteMask - tStart);
	    }
		return p;
	}

	private static <S extends MessageSchema<S>> void copyASCIIToBytes2(CharSequence source, int sourceIdx,
			final int sourceLen, Pipe<S> rbRingBuffer, final int p,
			byte[] target, int tStart, int len1) {
		if (len1>=sourceLen) {
			Pipe.copyASCIIToByte(source, sourceIdx, target, tStart, sourceLen);
		} else {
		    // done as two copies
		    Pipe.copyASCIIToByte(source, sourceIdx, target, tStart, len1);
		    Pipe.copyASCIIToByte(source, sourceIdx + len1, target, 0, sourceLen - len1);
		}
		rbRingBuffer.blobRingHead.byteWorkingHeadPos.value =  BYTES_WRAP_MASK&(p + sourceLen);
	}

    public static <S extends MessageSchema<S>> int copyASCIIToBytes(char[] source, int sourceIdx, final int sourceLen, Pipe<S> rbRingBuffer) {
		final int p = rbRingBuffer.blobRingHead.byteWorkingHeadPos.value;
	    if (sourceLen > 0) {
	    	int targetMask = rbRingBuffer.byteMask;
	    	byte[] target = rbRingBuffer.blobRing;

	        int tStart = p & targetMask;
	        int len1 = 1+targetMask - tStart;

			if (len1>=sourceLen) {
				copyASCIIToByte(source, sourceIdx, target, tStart, sourceLen);
			} else {
			    // done as two copies
			    copyASCIIToByte(source, sourceIdx, target, tStart, 1+ targetMask - tStart);
			    copyASCIIToByte(source, sourceIdx + len1, target, 0, sourceLen - len1);
			}
	        rbRingBuffer.blobRingHead.byteWorkingHeadPos.value =  BYTES_WRAP_MASK&(p + sourceLen);
	    }
		return p;
	}

	private static void copyASCIIToByte(char[] source, int sourceIdx, byte[] target, int targetIdx, int len) {
		int i = len;
		while (--i>=0) {
			target[targetIdx+i] = (byte)(0xFF&source[sourceIdx+i]);
		}
	}

	private static void copyASCIIToByte(CharSequence source, int sourceIdx, byte[] target, int targetIdx, int len) {
		int i = len;
		while (--i>=0) {
			target[targetIdx+i] = (byte)(0xFF&source.charAt(sourceIdx+i));
		}
	}

	public static <S extends MessageSchema<S>> void addUTF8(CharSequence source, Pipe<S> rb) {
	    addUTF8(source, null==source? -1 : source.length(), rb);
	}

	public static <S extends MessageSchema<S>> void addUTF8(CharSequence source, int sourceCharCount, Pipe<S> rb) {
		addBytePosAndLen(rb, rb.blobRingHead.byteWorkingHeadPos.value, copyUTF8ToByte(source,0, sourceCharCount, rb));
	}

	public static <S extends MessageSchema<S>> void addUTF8(char[] source, int sourceCharCount, Pipe<S> rb) {
		addBytePosAndLen(rb, rb.blobRingHead.byteWorkingHeadPos.value, copyUTF8ToByte(source,sourceCharCount,rb));
	}

	/**
	 * WARNING: unlike the ASCII version this method returns bytes written and not the position
	 */
   public static <S extends MessageSchema<S>> int copyUTF8ToByte(CharSequence source, int sourceOffset, int sourceCharCount, Pipe<S> pipe) {
        if (sourceCharCount>0) {
            int byteLength = Pipe.copyUTF8ToByte(source, sourceOffset, pipe.blobRing, pipe.blobMask, pipe.blobRingHead.byteWorkingHeadPos.value, sourceCharCount);
            pipe.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(pipe.blobRingHead.byteWorkingHeadPos.value+byteLength);
            return byteLength;
        } else {
            return 0;
        }
    }

	private static int copyUTF8ToByte(CharSequence source, int sourceIdx, byte[] target, int targetMask, int targetIdx, int charCount) {
	    int pos = targetIdx;
	    int c = 0;
	    while (c < charCount) {
	        pos = encodeSingleChar((int) source.charAt(sourceIdx+c++), target, targetMask, pos);
	    }
	    return pos - targetIdx;
	}

	/**
	 * WARNING: unlike the ASCII version this method returns bytes written and not the position
	 */
	public static <S extends MessageSchema<S>> int copyUTF8ToByte(char[] source, int sourceCharCount, Pipe<S> rb) {
		int byteLength = Pipe.copyUTF8ToByte(source, 0, rb.blobRing, rb.blobMask, rb.blobRingHead.byteWorkingHeadPos.value, sourceCharCount);
		rb.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(rb.blobRingHead.byteWorkingHeadPos.value+byteLength);
		return byteLength;
	}

	public static <S extends MessageSchema<S>> int copyUTF8ToByte(char[] source, int sourceOffset, int sourceCharCount, Pipe<S> rb) {
	    int byteLength = Pipe.copyUTF8ToByte(source, sourceOffset, rb.blobRing, rb.blobMask, rb.blobRingHead.byteWorkingHeadPos.value, sourceCharCount);
	    rb.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(rb.blobRingHead.byteWorkingHeadPos.value+byteLength);
	    return byteLength;
	}

	private static <S extends MessageSchema<S>> int copyUTF8ToByte(char[] source, int sourceIdx, byte[] target, int targetMask, int targetIdx, int charCount) {

	    int pos = targetIdx;
	    int c = 0;
	    while (c < charCount) {
	        pos = encodeSingleChar((int) source[sourceIdx+c++], target, targetMask, pos);
	    }
	    return pos - targetIdx;
	}

	public static <S extends MessageSchema<S>> int encodeSingleChar(int c, byte[] buffer,int mask, int pos) {

	    if (c <= 0x007F) { // less than or equal to 7 bits or 127
	        // code point 7
	        buffer[mask&pos++] = (byte) c;
	    } else {
	        if (c <= 0x07FF) { // less than or equal to 11 bits or 2047
	            // code point 11
	            buffer[mask&pos++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
	        } else {
	            if (c <= 0xFFFF) { // less than or equal to  16 bits or 65535

	            	//special case logic here because we know that c > 7FF and c <= FFFF so it may hit these
	            	// D800 through DFFF are reserved for UTF-16 and must be encoded as an 63 (error)
	            	if (0xD800 == (0xF800&c)) {
	            		buffer[mask&pos++] = 63;
	            		return pos;
	            	}

	                // code point 16
	                buffer[mask&pos++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
	            } else {
	                pos = rareEncodeCase(c, buffer, mask, pos);
	            }
	            buffer[mask&pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
	        }
	        buffer[mask&pos++] = (byte) (0x80 | (c & 0x3F));
	    }
	    return pos;
	}

	private static <S extends MessageSchema<S>> int rareEncodeCase(int c, byte[] buffer, int mask, int pos) {
		if (c < 0x1FFFFF) {
		    // code point 21
		    buffer[mask&pos++] = (byte) (0xF0 | ((c >> 18) & 0x07));
		} else {
		    if (c < 0x3FFFFFF) {
		        // code point 26
		        buffer[mask&pos++] = (byte) (0xF8 | ((c >> 24) & 0x03));
		    } else {
		        if (c < 0x7FFFFFFF) {
		            // code point 31
		            buffer[mask&pos++] = (byte) (0xFC | ((c >> 30) & 0x01));
		        } else {
		            throw new UnsupportedOperationException("can not encode char with value: " + c);
		        }
		        buffer[mask&pos++] = (byte) (0x80 | ((c >> 24) & 0x3F));
		    }
		    buffer[mask&pos++] = (byte) (0x80 | ((c >> 18) & 0x3F));
		}
		buffer[mask&pos++] = (byte) (0x80 | ((c >> 12) & 0x3F));
		return pos;
	}

	public static <S extends MessageSchema<S>> void addByteBuffer(ByteBuffer source, Pipe<S> pipe) {
	    int bytePos = pipe.blobRingHead.byteWorkingHeadPos.value;
	    int len = -1;
	    if (null!=source) {
	    	if (source.hasRemaining()) {
	    		len = source.remaining();
	        	copyByteBuffer(source,source.remaining(),pipe);
	    	} else {
	    		len = 0;
	    	}
	    }
	    //System.out.println("len to write "+len+" text:"+  readUTF8Ring(pipe, len, new StringBuilder(), bytePos));

	    Pipe.addBytePosAndLen(pipe, bytePos, len);
	}

   public static <S extends MessageSchema<S>> void addByteBuffer(ByteBuffer source, int length, Pipe<S> rb) {
        int bytePos = rb.blobRingHead.byteWorkingHeadPos.value;
        int len = -1;
        if (null!=source && length>0) {
            len = length;
            copyByteBuffer(source,length,rb);
        }
        Pipe.addBytePosAndLen(rb, bytePos, len);
    }
	   
	public static <S extends MessageSchema<S>> void copyByteBuffer(ByteBuffer source, int length, Pipe<S> rb) {
		validateVarLength(rb, length);
		int idx = rb.blobRingHead.byteWorkingHeadPos.value & rb.byteMask;
		int partialLength = 1 + rb.byteMask - idx;
		//may need to wrap around ringBuffer so this may need to be two copies
		if (partialLength>=length) {
		    source.get(rb.blobRing, idx, length);
		} else {
		    //read from source and write into byteBuffer
		    source.get(rb.blobRing, idx, partialLength);
		    source.get(rb.blobRing, 0, length - partialLength);
		}
		rb.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(rb.blobRingHead.byteWorkingHeadPos.value + length);
	}

	public static <S extends MessageSchema<S>> void addByteArrayWithMask(final Pipe<S> outputRing, int mask, int len, byte[] data, int offset) {
		validateVarLength(outputRing, len);
		copyBytesFromToRing(data,offset,mask,outputRing.blobRing,PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos),outputRing.blobMask, len);
		addBytePosAndLenSpecial(outputRing, PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos),len);
		PaddedInt.set(outputRing.blobRingHead.byteWorkingHeadPos, BYTES_WRAP_MASK&(PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos) + len));
	}
	
    public static <S extends MessageSchema<S>> void setByteArrayWithMask(final Pipe<S> outputRing, int mask, int len, byte[] data, int offset, long slabPosition) {
	        validateVarLength(outputRing, len);
	        copyBytesFromToRing(data,offset,mask,outputRing.blobRing,PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos),outputRing.byteMask, len);
            setBytePosAndLen(slab(outputRing), outputRing.mask, slabPosition, PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos), len, bytesWriteBase(outputRing));
	        PaddedInt.set(outputRing.blobRingHead.byteWorkingHeadPos, BYTES_WRAP_MASK&(PaddedInt.get(outputRing.blobRingHead.byteWorkingHeadPos) + len));
	}

	public static <S extends MessageSchema<S>> int peek(int[] buf, long pos, int mask) {
        return buf[mask & (int)pos];
    }

    public static <S extends MessageSchema<S>> long peekLong(int[] buf, long pos, int mask) {

        return (((long) buf[mask & (int)pos]) << 32) | (((long) buf[mask & (int)(pos + 1)]) & 0xFFFFFFFFl);

    }

    public static <S extends MessageSchema<S>> boolean isShutdown(Pipe<S> pipe) {
    	return pipe.imperativeShutDown.get();
    }

    public static <S extends MessageSchema<S>> void shutdown(Pipe<S> pipe) {
    	if (!pipe.imperativeShutDown.getAndSet(true)) {
    		pipe.firstShutdownCaller = new PipeException("Shutdown called");
    	}

    }

    public static <S extends MessageSchema<S>> void addByteArray(byte[] source, int sourceIdx, int sourceLen, Pipe<S> rbRingBuffer) {

    	assert(sourceLen>=0);
    	validateVarLength(rbRingBuffer, sourceLen);

    	copyBytesFromToRing(source, sourceIdx, Integer.MAX_VALUE, rbRingBuffer.blobRing, rbRingBuffer.blobRingHead.byteWorkingHeadPos.value, rbRingBuffer.blobMask, sourceLen);

    	addBytePosAndLen(rbRingBuffer, rbRingBuffer.blobRingHead.byteWorkingHeadPos.value, sourceLen);
        rbRingBuffer.blobRingHead.byteWorkingHeadPos.value = BYTES_WRAP_MASK&(rbRingBuffer.blobRingHead.byteWorkingHeadPos.value + sourceLen);

    }

    public static <S extends MessageSchema<S>> void addNullByteArray(Pipe<S> rbRingBuffer) {
        addBytePosAndLen(rbRingBuffer, rbRingBuffer.blobRingHead.byteWorkingHeadPos.value, -1);
    }


    public static <S extends MessageSchema<S>> void addIntValue(int value, Pipe<S> rb) {
         assert(rb.slabRingHead.workingHeadPos.value <= Pipe.tailPosition(rb)+rb.sizeOfSlabRing);
         //TODO: not always working in deep structures, check offsets:  assert(isValidFieldTypePosition(rb, TypeMask.IntegerSigned, TypeMask.IntegerSignedOptional, TypeMask.IntegerUnsigned, TypeMask.IntegerUnsignedOptional, TypeMask.Decimal));
		 setValue(rb.slabRing,rb.slabMask,rb.slabRingHead.workingHeadPos.value++,value);
	}

	private static <S extends MessageSchema<S>> boolean isValidFieldTypePosition(Pipe<S> rb, int ... expected) {
		FieldReferenceOffsetManager from = Pipe.from(rb);
		
         if (from.hasSimpleMessagesOnly && !isForDynamicSchema(rb)) {
        	 long offset =  Pipe.workingHeadPosition(rb)-Pipe.headPosition(rb);
        	 int[] starts = from.messageStarts();
        	 int j = starts.length;
        	 boolean found = false;
        	 String[] suggestions = new String[j];
        	 while (--j>=0) {
        		 
        		 //TODO: after walking over longs and strings may be off, TODO: double check this before using it again.
        		 
        		 int idx = starts[j]+1; //skip over msg id field of fixed size.
        		 int rem = (int)(offset-1);//skipe over msg id 
        		 
        		 while (rem>0) {
        			 rem -= from.fragDataSize[idx++];
        		 }
        		 int type = TokenBuilder.extractType(from.tokens[idx]);
        		 suggestions[j]=(TokenBuilder.tokenToString(from.tokens[idx]));
        		 
        		 int x = expected.length;
        		 while (--x>=0) {
        			 found |= type==expected[x];
        		 }        		 
        	 }
        	 if (!found) {
        		 log.error("Field type mismatch, no messages have an {} in this position perhaps you wanted one of these {}", TypeMask.toString(expected), Arrays.toString(suggestions));
        		 return false;
        	 }        
         }
         return true;
	}
    
    public static <S extends MessageSchema<S>> void setIntValue(int value, Pipe<S> pipe, long position) {
        assert(pipe.slabRingHead.workingHeadPos.value <= Pipe.tailPosition(pipe)+pipe.sizeOfSlabRing);
        setValue(pipe.slabRing,pipe.slabMask,position,value);
   }

    //
    //TODO: URGENT, A, It may be much nicer to add a method called 'beginMessage' which does only the base work and then moves the cursor forward one.
    //         Then we can take the confirm write and it can go back and set the id. Also add asserts on all fiels that this happens first !!!
    //
    
    //TODO: How can we test that the msgIdx that is passed in is only applicable to S ?? we need a way to check this.
    
    //must be called by low-level API when starting a new message
    public static <S extends MessageSchema<S>> int addMsgIdx(Pipe<S> pipe, int msgIdx) {
         assert(Pipe.workingHeadPosition(pipe)<(Pipe.tailPosition(pipe)+ pipe.sizeOfSlabRing  /*    pipe.slabMask*/  )) : "Working position is now writing into published(unreleased) tail "+
                Pipe.workingHeadPosition(pipe)+"<"+Pipe.tailPosition(pipe)+"+"+pipe.sizeOfSlabRing /*pipe.slabMask*/+" total "+((Pipe.tailPosition(pipe)+pipe.slabMask));
        
         assert(pipe.slabRingHead.workingHeadPos.value <= ((long)pipe.sizeOfSlabRing)+Pipe.tailPosition(pipe)) : 
                "Tail is at: "+Pipe.tailPosition(pipe)+" and Head at: "+pipe.slabRingHead.workingHeadPos.value+" but they are too far apart because the pipe is only of size: "+pipe.sizeOfSlabRing+
                "\n Double check the calls to confirmLowLevelWrite that the right size is used, and confirm that hasRoomForWrite is called.  ";
         
    	 assert(msgIdx>=0) : "Call publishEOF() instead of this method";

     	//this MUST be done here at the START of a message so all its internal fragments work with the same base position
     	 markBytesWriteBase(pipe);

   // 	 assert(rb.llwNextHeadTarget<=rb.headPos.get() || rb.workingHeadPos.value<=rb.llwNextHeadTarget) : "Unsupported mix of high and low level API.";
            	 
     	 assert(null != pipe.slabRing) : "Pipe must be init before use";
     	 
		 pipe.slabRing[pipe.slabMask & (int)pipe.slabRingHead.workingHeadPos.value++] = msgIdx;
		 return Pipe.from(pipe).fragDataSize[msgIdx];
		 
	}

	public static <S extends MessageSchema<S>> void setValue(int[] buffer, int rbMask, long offset, int value) {
        buffer[rbMask & (int)offset] = value;
    }


	
    public static <S extends MessageSchema<S>> void addBytePosAndLen(Pipe<S> pipe, int position, int length) {
        addBytePosAndLenSpecial(pipe,position,length);
    }

    public static <S extends MessageSchema<S>> void addBytePosAndLenSpecial(Pipe<S> targetOutput, final int startBytePos, int bytesLength) {
        PaddedLong workingHeadPos = getWorkingHeadPositionObject(targetOutput);
        setBytePosAndLen(slab(targetOutput), targetOutput.slabMask, workingHeadPos.value, startBytePos, bytesLength, bytesWriteBase(targetOutput));
        PaddedLong.add(workingHeadPos, 2);
    }

	public static <S extends MessageSchema<S>> void setBytePosAndLen(int[] buffer, int rbMask, long ringPos,	int positionDat, int lengthDat, int baseBytePos) {
	   	//negative position is written as is because the internal array does not have any offset (but it could some day)
    	//positive position is written after subtracting the rbRingBuffer.bytesHeadPos.longValue()
    	if (positionDat>=0) {
    		assert((positionDat-baseBytePos)>=0);
    		buffer[rbMask & (int)ringPos] = (int)(positionDat-baseBytePos) & Pipe.BYTES_WRAP_MASK; //mask is needed for the negative case, does no harm in positive case
    	} else {
    		buffer[rbMask & (int)ringPos] = positionDat;
    	}
        buffer[rbMask & (int)(ringPos+1)] = lengthDat;
	}
	

	static <S extends MessageSchema<S>> int restorePosition(Pipe<S> pipe, int pos) {
		assert(pos>=0);
		return pos + Pipe.bytesReadBase(pipe);
	}

	
	/*
	 * WARNING: this method has side effect of moving byte pointer.
	 */
    public static <S extends MessageSchema<S>> int bytePosition(int meta, Pipe<S> pipe, int len) {
    	int pos =  restorePosition(pipe, meta & RELATIVE_POS_MASK);
        if (len>=0) {
        	Pipe.addAndGetBytesWorkingTailPosition(pipe, len);
        }        
        return pos;
    }
    
    //WARNING: this has no side effect
    public static <S extends MessageSchema<S>> int convertToPosition(int meta, Pipe<S> pipe) {
    	return restorePosition(pipe, meta & RELATIVE_POS_MASK);
    }


    public static <S extends MessageSchema> void addValue(int[] buffer, int rbMask, PaddedLong headCache, int value1, int value2, int value3) {

        long p = headCache.value;
        buffer[rbMask & (int)p++] = value1;
        buffer[rbMask & (int)p++] = value2;
        buffer[rbMask & (int)p++] = value3;
        headCache.value = p;

    }

    @Deprecated
    public static <S extends MessageSchema<S>> void addValues(int[] buffer, int rbMask, PaddedLong headCache, int value1, long value2) {

        headCache.value = setValues(buffer, rbMask, headCache.value, value1, value2);

    }

    public static <S extends MessageSchema<S>> void addDecimal(int exponent, long mantissa, Pipe<S> pipe) {
        pipe.slabRingHead.workingHeadPos.value = setValues(pipe.slabRing, pipe.mask, pipe.slabRingHead.workingHeadPos.value, exponent, mantissa);
    }


	static <S extends MessageSchema<S>> long setValues(int[] buffer, int rbMask, long pos, int value1, long value2) {
		buffer[rbMask & (int)pos++] = value1;
        buffer[rbMask & (int)pos++] = (int)(value2 >>> 32);
        buffer[rbMask & (int)pos++] = (int)(value2 & 0xFFFFFFFF);
		return pos;
	}

	@Deprecated //use addLongVlue(value, rb)
    public static <S extends MessageSchema<S>> void addLongValue(Pipe<S> pipe, long value) {
		 addLongValue(value, pipe);
	}

	public static <S extends MessageSchema<S>> void addLongValue(long value, Pipe<S> rb) {
		 addLongValue(rb.slabRing, rb.slabMask, rb.slabRingHead.workingHeadPos, value);
	}

    public static <S extends MessageSchema<S>> void addLongValue(int[] buffer, int rbMask, PaddedLong headCache, long value) {

        long p = headCache.value;
        buffer[rbMask & (int)p] = (int)(value >>> 32);
        buffer[rbMask & (int)(p+1)] = ((int)value);
        headCache.value = p+2;

    }

    static <S extends MessageSchema<S>> int readRingByteLen(int fieldPos, int[] rbB, int rbMask, long rbPos) {
        return rbB[(int) (rbMask & (rbPos + fieldPos + 1))];// second int is always the length
    }

	public static <S extends MessageSchema<S>> int readRingByteLen(int idx, Pipe<S> pipe) {
		return readRingByteLen(idx,pipe.slabRing, pipe.mask, pipe.slabRingTail.workingTailPos.value);
	}

	public static <S extends MessageSchema<S>> int takeRingByteLen(Pipe<S> pipe) {
	//    assert(ring.structuredLayoutRingTail.workingTailPos.value<RingBuffer.workingHeadPosition(pipe));
		return pipe.slabRing[(int)(pipe.slabMask & (pipe.slabRingTail.workingTailPos.value++))];// second int is always the length
	}



    public static <S extends MessageSchema<S>> byte[] byteBackingArray(int meta, Pipe<S> pipe) {
        return pipe.blobRingLookup[meta>>>31];
    }

	public static <S extends MessageSchema<S>> int readRingByteMetaData(int pos, Pipe<S> pipe) {
		return readValue(pos,pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value);
	}

	//TODO: must always read metadata before length, easy mistake to make, need assert to ensure this is caught if happens.
	public static <S extends MessageSchema<S>> int takeRingByteMetaData(Pipe<S> pipe) {
	//    assert(ring.structuredLayoutRingTail.workingTailPos.value<RingBuffer.workingHeadPosition(ring));
		return readValue(0,pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value++);
	}

    static <S extends MessageSchema<S>> int readValue(int fieldPos, int[] rbB, int rbMask, long rbPos) {
        return rbB[(int)(rbMask & (rbPos + fieldPos))];
    }

    //TODO: may want to deprecate this interface
    public static <S extends MessageSchema<S>> int readValue(int idx, Pipe<S> pipe) {
    	return readValue(idx, pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value);
    }

    public static <S extends MessageSchema<S>> int takeInt(Pipe<S> pipe) {
    	return readValue(pipe.slabRing, pipe.slabMask, pipe.slabRingTail.workingTailPos.value++);
    }
    
    @Deprecated //use takeInt
    public static <S extends MessageSchema<S>> int takeValue(Pipe<S> pipe) {
    	return takeInt(pipe);
    }
    
    public static <S extends MessageSchema<S>> int readValue(int[] rbB, int rbMask, long rbPos) {
        return rbB[(int)(rbMask & rbPos)];
    }
    
    public static <S extends MessageSchema<S>> Integer takeOptionalValue(Pipe<S> pipe) {
        int absent32Value = FieldReferenceOffsetManager.getAbsent32Value(Pipe.from(pipe));
        return takeOptionalValue(pipe, absent32Value);
    }

    public static <S extends MessageSchema<S>> Integer takeOptionalValue(Pipe<S> pipe, int absent32Value) {
        int temp = readValue(0, pipe.slabRing, pipe.slabMask, pipe.slabRingTail.workingTailPos.value++);
        return absent32Value!=temp ? new Integer(temp) : null;
    }

    public static <S extends MessageSchema<S>> long takeLong(Pipe<S> pipe) {
        
        //this assert does not always work because the head position is volatile, Not sure what should be done to resolve it.  
        //assert(ring.slabRingTail.workingTailPos.value<Pipe.workingHeadPosition(ring)) : "working tail "+ring.slabRingTail.workingTailPos.value+" but head is "+Pipe.workingHeadPosition(ring);
    	
        long result = readLong(pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value);
    	pipe.slabRingTail.workingTailPos.value+=2;
    	return result;
    }
    
    public static <S extends MessageSchema<S>> Long takeOptionalLong(Pipe<S> pipe) {
        long absent64Value = FieldReferenceOffsetManager.getAbsent64Value(Pipe.from(pipe));
        return takeOptionalLong(pipe, absent64Value);
    }

    public static <S extends MessageSchema<S>> Long takeOptionalLong(Pipe<S> pipe, long absent64Value) {
        assert(pipe.slabRingTail.workingTailPos.value<Pipe.workingHeadPosition(pipe)) : "working tail "+pipe.slabRingTail.workingTailPos.value+" but head is "+Pipe.workingHeadPosition(pipe);
        long result = readLong(pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value);
        pipe.slabRingTail.workingTailPos.value+=2;
        return absent64Value!=result ? new Long(result) : null;
    }
    

    public static <S extends MessageSchema<S>> long readLong(int idx, Pipe<S> pipe) {
    	return readLong(pipe.slabRing,pipe.mask,idx+pipe.slabRingTail.workingTailPos.value);

    }

    public static <S extends MessageSchema<S>> int takeMsgIdx(Pipe<S> pipe) {
        
        /**
         * TODO: mask the result int to only the bits which contain the msgId.
         *       The other bits can bet retrieved by the getMessagePackedBits
         *       The limit for the byte length is also known so there is 
         *       another method getFragmentPackedBits which come from the tail.
         *   
         *       This bits must be defined in the template/FROM and bounds checked at compile time.
         * 
         */
        
        
       // assert(pipe.slabRingTail.workingTailPos.value<Pipe.workingHeadPosition(pipe)) : " tail is "+pipe.slabRingTail.workingTailPos.value+" but head is "+Pipe.workingHeadPosition(pipe);
    	return pipe.lastMsgIdx = readValue(pipe.slabRing, pipe.slabMask, pipe.slabRingTail.workingTailPos.value++);
    }
    
    public static <S extends MessageSchema<S>> boolean peekMsg(Pipe<S> pipe, int expected) {
        return Pipe.hasContentToRead(pipe) && peekInt(pipe)==expected;    	
    }
    
    public static <S extends MessageSchema<S>> boolean peekNotMsg(Pipe<S> pipe, int expected) {
        return Pipe.hasContentToRead(pipe) && peekInt(pipe)!=expected;    	
    }    

    public static <S extends MessageSchema<S>> boolean peekMsg(Pipe<S> pipe, int expected1, int expected2) {
        return Pipe.hasContentToRead(pipe) && (peekInt(pipe)==expected1 || peekInt(pipe)==expected2);
    }
    
    public static <S extends MessageSchema<S>> int peekInt(Pipe<S> pipe) {
    	assert(Pipe.hasContentToRead(pipe)) : "results would not be repeatable";
        return readValue(pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value);
    }
    
    public static <S extends MessageSchema<S>> int peekInt(Pipe<S> pipe, int offset) {
    	assert(Pipe.hasContentToRead(pipe)) : "results would not be repeatable";
        return readValue(pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value+offset);
    }
   
    public static <S extends MessageSchema<S>> long peekLong(Pipe<S> pipe, int offset) {
    	assert(Pipe.hasContentToRead(pipe)) : "results would not be repeatable";
        return readLong(pipe.slabRing,pipe.slabMask,pipe.slabRingTail.workingTailPos.value+offset);
    }
    
    
    public static <S extends MessageSchema<S>> int contentRemaining(Pipe<S> pipe) {
        int result = (int)(pipe.slabRingHead.headPos.get() - pipe.slabRingTail.tailPos.get()); //must not go past add count because it is not release yet.
        assert(result>=0) : "content remaining must never be negative";
        return result;
    }

    public static <S extends MessageSchema<S>> int releaseReadLock(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        int bytesConsumedByFragment = takeInt(pipe);
        assert(bytesConsumedByFragment>=0) : "Bytes consumed by fragment must never be negative, was fragment written correctly?, is read positioned correctly?";
        Pipe.markBytesReadBase(pipe, bytesConsumedByFragment);  //the base has been moved so we can also use it below.
        assert(Pipe.contentRemaining(pipe)>=0); 
        batchedReleasePublish(pipe, 
        		              pipe.blobRingTail.byteWorkingTailPos.value = pipe.blobReadBase, 
        		              pipe.slabRingTail.workingTailPos.value);
        assert(validateInsideData(pipe, pipe.blobReadBase));
        return bytesConsumedByFragment;        
    }
    
    public static <S extends MessageSchema<S>> int readNextWithoutReleasingReadLock(Pipe<S> pipe) {
        int bytesConsumedByFragment = takeInt(pipe); 
        Pipe.markBytesReadBase(pipe, bytesConsumedByFragment); //the base has been moved so we can also use it below.
        assert(Pipe.contentRemaining(pipe)>=0);
        PendingReleaseData.appendPendingReadRelease(pipe.pendingReleases,
                                                    pipe.slabRingTail.workingTailPos.value, 
                                                    pipe.blobRingTail.byteWorkingTailPos.value = pipe.blobReadBase, 
                                                    bytesConsumedByFragment);
        assert(validateInsideData(pipe, pipe.blobReadBase));
        return bytesConsumedByFragment;   
    }
    

    @Deprecated //inline and use releaseReadLock(pipe)
    public static <S extends MessageSchema<S>> int releaseReads(Pipe<S> pipe) {    	
        return releaseReadLock(pipe);     
    }

    public static <S extends MessageSchema<S>> void batchedReleasePublish(Pipe<S> pipe, int blobTail, long slabTail) {
        assert(null==pipe.ringWalker || pipe.ringWalker.cursor<=0 && !PipeReader.isNewMessage(pipe.ringWalker)) : "Unsupported mix of high and low level API.  ";
        releaseBatchedReads(pipe, blobTail, slabTail);
    }
    
    static <S extends MessageSchema<S>> void releaseBatchedReads(Pipe<S> pipe, int workingBlobRingTailPosition, long nextWorkingTail) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        if (decBatchRelease(pipe)<=0) { 
           setBytesTail(pipe, workingBlobRingTailPosition);
           
           //NOTE: the working tail is in use as part of the read and should not be modified
           //      this method only modifies the externally visible tail to let writers see it.
           pipe.slabRingTail.tailPos.lazySet(nextWorkingTail);
           
           beginNewReleaseBatch(pipe); 
           
           assert(validateInsideData(pipe, pipe.blobReadBase));
           
        } else {
           storeUnpublishedTail(pipe, nextWorkingTail, workingBlobRingTailPosition);            
        }
    }

    static <S extends MessageSchema<S>> void storeUnpublishedTail(Pipe<S> pipe, long workingTailPos, int byteWorkingTailPos) {
        pipe.lastReleasedBlobTail = byteWorkingTailPos;
        pipe.lastReleasedSlabTail = workingTailPos;
    }
   
        

    /**
     * Release any reads that were held back due to batching.
     * @param pipe
     */
    public static <S extends MessageSchema<S>> void releaseAllBatchedReads(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        if (pipe.lastReleasedSlabTail > pipe.slabRingTail.tailPos.get()) {
            PaddedInt.set(pipe.blobRingTail.bytesTailPos,pipe.lastReleasedBlobTail);
            pipe.slabRingTail.tailPos.lazySet(pipe.lastReleasedSlabTail);
            pipe.batchReleaseCountDown = pipe.batchReleaseCountDownInit;
        }

        assert(debugHeadAssignment(pipe));
    }
    
    public static <S extends MessageSchema<S>> void releaseBatchedReadReleasesUpToThisPosition(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        long newTailToPublish = Pipe.getWorkingTailPosition(pipe);
        int newTailBytesToPublish = Pipe.getWorkingBlobRingTailPosition(pipe);
        
        //int newTailBytesToPublish = RingBuffer.bytesReadBase(ring);
        
        releaseBatchedReadReleasesUpToPosition(pipe, newTailToPublish, newTailBytesToPublish);
                
    }

    public static <S extends MessageSchema<S>> void releaseBatchedReadReleasesUpToPosition(Pipe<S> pipe, long newTailToPublish,  int newTailBytesToPublish) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
    	assert(newTailToPublish<=pipe.lastReleasedSlabTail) : "This new value is forward of the next Release call, eg its too large";
        assert(newTailToPublish>=pipe.slabRingTail.tailPos.get()) : "This new value is behind the existing published Tail, eg its too small ";
        
//        //TODO: These two asserts would be nice to have but the int of bytePos wraps every 2 gig causing false positives, these need more mask logic to be right
//        assert(newTailBytesToPublish<=ring.lastReleasedBytesTail) : "This new value is forward of the next Release call, eg its too large";
//        assert(newTailBytesToPublish>=ring.unstructuredLayoutRingTail.bytesTailPos.value) : "This new value is behind the existing published Tail, eg its too small ";
//        assert(newTailBytesToPublish<=ring.bytesWorkingTailPosition(ring)) : "Out of bounds should never be above working tail";
//        assert(newTailBytesToPublish<=ring.bytesHeadPosition(ring)) : "Out of bounds should never be above head";
        
        
        PaddedInt.set(pipe.blobRingTail.bytesTailPos, newTailBytesToPublish);
        pipe.slabRingTail.tailPos.lazySet(newTailToPublish);
        pipe.batchReleaseCountDown = pipe.batchReleaseCountDownInit;
    }

    @Deprecated
	public static <S extends MessageSchema<S>> void releaseAll(Pipe<S> pipe) {
    	    assert(Pipe.singleThreadPerPipeRead(pipe.id));
			int i = pipe.blobRingTail.byteWorkingTailPos.value= pipe.blobRingHead.byteWorkingHeadPos.value;
            PaddedInt.set(pipe.blobRingTail.bytesTailPos,i);
			pipe.slabRingTail.tailPos.lazySet(pipe.slabRingTail.workingTailPos.value= pipe.slabRingHead.workingHeadPos.value);

    }

    /**
     * Low level API for publish
     * @param pipe
     */
    public static <S extends MessageSchema<S>> int publishWrites(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeWrite(pipe.id));
    	//happens at the end of every fragment
        int consumed = writeTrailingCountOfBytesConsumed(pipe); //increment because this is the low-level API calling

		publishWritesBatched(pipe);

		return consumed;
    }

	public static <S extends MessageSchema<S>> int writeTrailingCountOfBytesConsumed(Pipe<S> pipe) {
		return writeTrailingCountOfBytesConsumed(pipe, pipe.slabRingHead.workingHeadPos.value++);
	}

    public static <S extends MessageSchema<S>> int publishWrites(Pipe<S> pipe, int optionalHiddenTrailingBytes) {
    	assert(Pipe.singleThreadPerPipeWrite(pipe.id));
    	//add a few extra bytes on the end of the blob so we can "hide" information between fragments.
    	assert(optionalHiddenTrailingBytes>=0) : "only zero or positive values supported";
		PaddedInt.maskedAdd(pipe.blobRingHead.byteWorkingHeadPos, optionalHiddenTrailingBytes, Pipe.BYTES_WRAP_MASK);
    	    	
    	//happens at the end of every fragment
        int consumed = writeTrailingCountOfBytesConsumed(pipe, pipe.slabRingHead.workingHeadPos.value++); //increment because this is the low-level API calling
        assert(consumed<pipe.maxVarLen) : "When hiding data it must stay below the max var length threshold when added to the rest of the fields.";        
        
		publishWritesBatched(pipe);
		return consumed;
    }
    
    
    public static <S extends MessageSchema<S>> void publishWritesBatched(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeWrite(pipe.id));
        //single length field still needs to move this value up, so this is always done
    	//in most cases this is redundant
		pipe.blobWriteLastConsumedPos = pipe.blobRingHead.byteWorkingHeadPos.value;

    	publishHeadPositions(pipe);
    }


    private static <S extends MessageSchema<S>> boolean validateFieldCount(Pipe<S> pipe) {
        long lastHead = Math.max(pipe.lastPublishedSlabRingHead,  Pipe.headPosition(pipe));    	
    	int len = (int)(Pipe.workingHeadPosition(pipe)-lastHead);    	
    	int[] fragDataSize = Pipe.from(pipe).fragDataSize;
        int i = fragDataSize.length;
        boolean found = false;
    	while (--i>=0) {
    	    found |= (len==fragDataSize[i]);
    	}    	
    	if (!found) {
    	    System.err.println("there is no fragment of size "+len+" check for missing fields. "+pipe.schema.getClass().getSimpleName()); 
    	}
        return found;
    }

    /**
     * Publish any writes that were held back due to batching.
     * @param pipe
     */
    public static <S extends MessageSchema<S>> void publishAllBatchedWrites(Pipe<S> pipe) {

    	if (pipe.lastPublishedSlabRingHead>pipe.slabRingHead.headPos.get()) {
    		PaddedInt.set(pipe.blobRingHead.bytesHeadPos,pipe.lastPublishedBlobRingHead);
    		pipe.slabRingHead.headPos.lazySet(pipe.lastPublishedSlabRingHead);
    	}

		assert(debugHeadAssignment(pipe));
		pipe.batchPublishCountDown = pipe.batchPublishCountDownInit;
    }


	private static <S extends MessageSchema<S>> boolean debugHeadAssignment(Pipe<S> pipe) {

		if (0!=(PipeConfig.SHOW_HEAD_PUBLISH&pipe.debugFlags) ) {
			new Exception("Debug stack for assignment of published head positition"+pipe.slabRingHead.headPos.get()).printStackTrace();
		}
		return true;
	}


	public static <S extends MessageSchema<S>> void publishHeadPositions(Pipe<S> pipe) {

		assert(pipe.slabRingHead.workingHeadPos.value >= Pipe.headPosition(pipe));
    	assert(pipe.llWrite.llrConfirmedPosition<=Pipe.headPosition(pipe) || 
    		   pipe.slabRingHead.workingHeadPos.value<=pipe.llWrite.llrConfirmedPosition) :
    			   "Possible unsupported mix of high and low level API. NextHead>head and workingHead>nextHead "+pipe+" nextHead "+pipe.llWrite.llrConfirmedPosition+"\n"+
    		       "OR the XML field types may not match the accessor methods in use.";
    	assert(validateFieldCount(pipe)) : "No fragment could be found with this field count, check for missing or extra fields.";

	    //TODO: need way to test if publish was called on an input ? may be much easer to detect missing publish. or extra release.
	    if ((--pipe.batchPublishCountDown<=0)) {
	        PaddedInt.set(pipe.blobRingHead.bytesHeadPos,pipe.blobRingHead.byteWorkingHeadPos.value);
	        pipe.slabRingHead.headPos.lazySet(pipe.slabRingHead.workingHeadPos.value);
	        assert(debugHeadAssignment(pipe));
	        pipe.batchPublishCountDown = pipe.batchPublishCountDownInit;
	    } else {
	        storeUnpublishedWrites(pipe);
	    }
	}

	public static <S extends MessageSchema<S>> void storeUnpublishedWrites(Pipe<S> pipe) {
		pipe.lastPublishedBlobRingHead = pipe.blobRingHead.byteWorkingHeadPos.value;
		pipe.lastPublishedSlabRingHead = pipe.slabRingHead.workingHeadPos.value;
	}

    public static <S extends MessageSchema<S>> void abandonWrites(Pipe<S> pipe) {
        //ignore the fact that any of this was written to the ring buffer
    	pipe.slabRingHead.workingHeadPos.value = pipe.slabRingHead.headPos.longValue();
    	pipe.blobRingHead.byteWorkingHeadPos.value = PaddedInt.get(pipe.blobRingHead.bytesHeadPos);
    	storeUnpublishedWrites(pipe);
    }

    /**
     * Blocks until there is enough room for this first fragment of the message and records the messageId.
     * @param pipe
     * @param msgIdx
     */
	public static <S extends MessageSchema<S>> void blockWriteMessage(Pipe<S> pipe, int msgIdx) {
		//before write make sure the tail is moved ahead so we have room to write
	    spinBlockForRoom(pipe, Pipe.from(pipe).fragDataSize[msgIdx]);
		Pipe.addMsgIdx(pipe, msgIdx);
	}


    //All the spin lock methods share the same implementation. Unfortunately these can not call
    //a common implementation because the extra method jump degrades the performance in tight loops
    //where these spin locks are commonly used.

    public static <S extends MessageSchema<S>> void spinBlockForRoom(Pipe<S> pipe, int size) {
        while (!hasRoomForWrite(pipe, size)) {
            spinWork(pipe);
        }
    }

    @Deprecated //use spinBlockForRoom then confirm the write afterwords
    public static <S extends MessageSchema<S>> long spinBlockOnTail(long lastCheckedValue, long targetValue, Pipe<S> pipe) {
    	while (null==pipe.slabRing || lastCheckedValue < targetValue) {
    		spinWork(pipe);
		    lastCheckedValue = pipe.slabRingTail.tailPos.longValue();
		}
		return lastCheckedValue;
    }

    public static <S extends MessageSchema<S>> void spinBlockForContent(Pipe<S> pipe) {
        while (!hasContentToRead(pipe)) {
            spinWork(pipe);
        }
    }

    //Used by RingInputStream to duplicate contract behavior,  TODO: AA rename to waitForAvailableContent or blockUntilContentReady?
    public static <S extends MessageSchema<S>> long spinBlockOnHead(long lastCheckedValue, long targetValue, Pipe<S> pipe) {
    	while ( lastCheckedValue < targetValue) {
    		spinWork(pipe);
		    lastCheckedValue = pipe.slabRingHead.headPos.get();
		}
		return lastCheckedValue;
    }

	static <S extends MessageSchema<S>> void spinWork(Pipe<S> pipe) {
		Thread.yield();//needed for now but re-evaluate performance impact
		if (isShutdown(pipe) || Thread.currentThread().isInterrupted()) {
			Thread.currentThread().interrupt();
			throw null!=pipe.firstShutdownCaller ? pipe.firstShutdownCaller : new PipeException("Unexpected shutdown");
		}
	}

	public static <S extends MessageSchema<S>> int blobMask(Pipe<S> pipe) {
		return pipe.blobMask;
	}
	
	public static <S extends MessageSchema<S>> int slabMask(Pipe<S> pipe) {
	    return pipe.slabMask;
	}

	public static <S extends MessageSchema<S>> long getSlabHeadPosition(Pipe<S> pipe) {
		return headPosition(pipe);
	}
	
	public static <S extends MessageSchema<S>> long headPosition(Pipe<S> pipe) {
		 return pipe.slabRingHead.headPos.get();
	}

    public static <S extends MessageSchema<S>> long workingHeadPosition(Pipe<S> pipe) {
        return PaddedLong.get(pipe.slabRingHead.workingHeadPos);
    }

    public static <S extends MessageSchema<S>> void setWorkingHead(Pipe<S> pipe, long value) {
    	//assert(pipe.slabRingHead.workingHeadPos.value<=value) : "new working head must be forward";
        PaddedLong.set(pipe.slabRingHead.workingHeadPos, value);
    }

    public static <S extends MessageSchema<S>> long addAndGetWorkingHead(Pipe<S> pipe, int inc) {
        return PaddedLong.add(pipe.slabRingHead.workingHeadPos, inc);
    }

    public static <S extends MessageSchema<S>> long getWorkingTailPosition(Pipe<S> pipe) {
        return PaddedLong.get(pipe.slabRingTail.workingTailPos);
    }

    public static <S extends MessageSchema<S>> void setWorkingTailPosition(Pipe<S> pipe, long value) {
        PaddedLong.set(pipe.slabRingTail.workingTailPos, value);
    }

    public static <S extends MessageSchema<S>> long addAndGetWorkingTail(Pipe<S> pipe, int inc) {
        return PaddedLong.add(pipe.slabRingTail.workingTailPos, inc);
    }


	/**
	 * This method is only for build transfer stages that require direct manipulation of the position.
	 * Only call this if you really know what you are doing.
	 * @param pipe
	 * @param workingHeadPos
	 */
	public static <S extends MessageSchema<S>> void publishWorkingHeadPosition(Pipe<S> pipe, long workingHeadPos) {
		pipe.slabRingHead.headPos.lazySet(pipe.slabRingHead.workingHeadPos.value = workingHeadPos);
	}

	public static <S extends MessageSchema<S>> long tailPosition(Pipe<S> pipe) {
		return pipe.slabRingTail.tailPos.get();
	}



	/**
	 * This method is only for build transfer stages that require direct manipulation of the position.
	 * Only call this if you really know what you are doing.
	 * @param pipe
	 * @param workingTailPos
	 */
	public static <S extends MessageSchema<S>> void publishWorkingTailPosition(Pipe<S> pipe, long workingTailPos) {
		pipe.slabRingTail.tailPos.lazySet(pipe.slabRingTail.workingTailPos.value = workingTailPos);
	}
    
	public static <S extends MessageSchema<S>> void publishBlobWorkingTailPosition(Pipe<S> pipe, int blobWorkingTailPos) {
        pipe.blobRingTail.bytesTailPos.value = (pipe.blobRingTail.byteWorkingTailPos.value = blobWorkingTailPos);
    }
	
	@Deprecated
	public static <S extends MessageSchema<S>> int primarySize(Pipe<S> pipe) {
		return pipe.sizeOfSlabRing;
	}

	public static <S extends MessageSchema<S>> FieldReferenceOffsetManager from(Pipe<S> pipe) {
		assert(pipe.schema!=null);
		assert(pipe.schema.from!=null);		
		return pipe.schema.from;
	}

	public static <S extends MessageSchema<S>> int cursor(Pipe<S> pipe) {
        return pipe.ringWalker.cursor;
    }

	public static <S extends MessageSchema<S>> int writeTrailingCountOfBytesConsumed(Pipe<S> pipe, final long pos) {

		final int consumed = computeCountOfBytesConsumed(pipe);
		
		pipe.slabRing[pipe.slabMask & (int)pos] = consumed;
		pipe.blobWriteLastConsumedPos = pipe.blobRingHead.byteWorkingHeadPos.value;
		return consumed;
	}

	public static <S extends MessageSchema<S>> int computeCountOfBytesConsumed(Pipe<S> pipe) {
		int consumed = pipe.blobRingHead.byteWorkingHeadPos.value - pipe.blobWriteLastConsumedPos;	
		
		if (consumed<0) {			
			consumed = (1+consumed)+Integer.MAX_VALUE;			
		}	
		assert(consumed>=0) : "consumed was "+consumed;
		//log.trace("wrote {} bytes consumed to position {}",consumed,pos);
		return consumed;
	}

	public static <S extends MessageSchema<S>> IntBuffer wrappedSlabRing(Pipe<S> pipe) {
		return pipe.wrappedSlabRing;
	}

	public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobRingA(Pipe<S> pipe) {
		return pipe.wrappedBlobReadingRingA;
	}

    public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobRingB(Pipe<S> pipe) {
        return pipe.wrappedBlobReadingRingB;
    }

	public static <S extends MessageSchema<S>> ByteBuffer wrappedBlobConstBuffer(Pipe<S> pipe) {
		return pipe.wrappedBlobConstBuffer;
	}

	
	public static <S extends MessageSchema<S>> DataOutputBlobWriter<S> outputStream(Pipe<S> pipe) {
		return pipe.blobWriter;
	}

	public static <S extends MessageSchema<S>> DataInputBlobReader<S> inputStream(Pipe<S> pipe) {
		return pipe.blobReader;
	}
	
	/////////////
	//low level API
	////////////



	@Deprecated
	public static <S extends MessageSchema<S>> boolean roomToLowLevelWrite(Pipe<S> pipe, int size) {
		return hasRoomForWrite(pipe, size);
	}

	//This holds the last known state of the tail position, if its sufficiently far ahead it indicates that
	//we do not need to fetch it again and this reduces contention on the CAS with the reader.
	//This is an important performance feature of the low level API and should not be modified.
    public static <S extends MessageSchema<S>> boolean hasRoomForWrite(Pipe<S> pipe, int size) {
    	assert(Pipe.singleThreadPerPipeWrite(pipe.id));
        return roomToLowLevelWrite(pipe, pipe.llRead.llwConfirmedPosition+size);
    }
    
    public static <S extends MessageSchema<S>> void presumeRoomForWrite(Pipe<S> pipe) {
    	if (!hasRoomForWrite(pipe)) {
    		log.warn("Assumed available space but not found, make pipe larger or write less {}",pipe);    		
    		while (!hasRoomForWrite(pipe)) {
    			spinWork(pipe);
    		}
    	}
    }
    
    public static <S extends MessageSchema<S>> boolean hasRoomForWrite(Pipe<S> pipe) {
        assert(null != pipe.slabRing) : "Pipe must be init before use";
        assert(Pipe.singleThreadPerPipeWrite(pipe.id));
        return roomToLowLevelWrite(pipe, pipe.llRead.llwConfirmedPosition+FieldReferenceOffsetManager.maxFragmentSize(Pipe.from(pipe)));
    }    
    
	private static <S extends MessageSchema<S>> boolean roomToLowLevelWrite(Pipe<S> pipe, long target) {
		//only does second part if the first does not pass
		return (pipe.llRead.llrTailPosCache > target) || roomToLowLevelWriteSlow(pipe, target);
	}

	private static <S extends MessageSchema<S>> boolean roomToLowLevelWriteSlow(Pipe<S> pipe, long target) {
        return (pipe.llRead.llrTailPosCache = pipe.slabRingTail.tailPos.get()  ) >= target;
	}

	public static <S extends MessageSchema<S>> long confirmLowLevelWrite(Pipe<S> output, int size) { 
	 
		assert(Pipe.singleThreadPerPipeWrite(output.id));
	    assert(size>=0) : "unsupported size "+size;
	    
	    assert((output.llRead.llwConfirmedPosition+output.slabMask) <= Pipe.workingHeadPosition(output)) : " confirmed writes must be less than working head position writes:"
	                                                +(output.llRead.llwConfirmedPosition+output.slabMask)+" workingHead:"+Pipe.workingHeadPosition(output)+
	                                                " \n CHECK that Pipe is written same fields as message defines and skips none!";
	   
	    assert(verifySize(output, size));
	   	    
	    return  output.llRead.llwConfirmedPosition += size;

	}

	/**
	 * Method used for moving more than one fragment at a time. Any size value will be acceptable
	 */
	public static <S extends MessageSchema<S>> long confirmLowLevelWriteUnchecked(Pipe<S> output, int size) { 
		 
		assert(Pipe.singleThreadPerPipeWrite(output.id));
	    assert(size>=0) : "unsupported size "+size;
	    
	    assert((output.llRead.llwConfirmedPosition+output.slabMask) <= Pipe.workingHeadPosition(output)) : " confirmed writes must be less than working head position writes:"
	                                                +(output.llRead.llwConfirmedPosition+output.slabMask)+" workingHead:"+Pipe.workingHeadPosition(output)+
	                                                " \n CHECK that Pipe is written same fields as message defines and skips none!";
	   	    
	    return  output.llRead.llwConfirmedPosition += size;

	}
	
	private static <S extends MessageSchema<S>> boolean verifySize(Pipe<S> output, int size) {
		try {
			assert(Pipe.sizeOf(output, output.slabRing[output.slabMask&(int)output.llRead.llwConfirmedPosition]) == size) : 
				"Did not write the same size fragment as expected, double check message. expected:"+Pipe.sizeOf(output, output.slabRing[output.slabMask&(int)output.llRead.llwConfirmedPosition])+" but got "+size;
		} catch (ArrayIndexOutOfBoundsException aiex) {
			//ignore, caused by some poor unit tests which need to be re-written.
		}
		return true;
	}

	//helper method always uses the right size but that value needs to be found so its a bit slower than if you already knew the size and passed it in
	public static <S extends MessageSchema<S>> long confirmLowLevelWrite(Pipe<S> output) { 
		 
		assert(Pipe.singleThreadPerPipeWrite(output.id));
	    assert((output.llRead.llwConfirmedPosition+output.slabMask) <= Pipe.workingHeadPosition(output)) : " confirmed writes must be less than working head position writes:"
	                                                +(output.llRead.llwConfirmedPosition+output.slabMask)+" workingHead:"+Pipe.workingHeadPosition(output)+
	                                                " \n CHECK that Pipe is written same fields as message defines and skips none!";
	   
	    return  output.llRead.llwConfirmedPosition += Pipe.sizeOf(output, output.slabRing[output.slabMask&(int)output.llRead.llwConfirmedPosition]);

	}
	
	
	//do not use with high level API, is dependent on low level confirm calls.
	public static <S extends MessageSchema<S>> boolean hasContentToRead(Pipe<S> pipe, int size) {
		assert(Pipe.singleThreadPerPipeRead(pipe.id));
        //optimized for the other method without size. this is why the -1 is there and we use > for target comparison.
        return contentToLowLevelRead2(pipe, pipe.llWrite.llrConfirmedPosition+size-1, pipe.llWrite); 
    }
    
	//this method can only be used with low level api navigation loop
	//CAUTION: THIS IS NOT COMPATIBLE WITH PipeReader behavior...
    public static <S extends MessageSchema<S>> boolean hasContentToRead(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        assert(null != pipe.slabRing) : "Pipe must be init before use";
        final boolean result = contentToLowLevelRead2(pipe, pipe.llWrite.llrConfirmedPosition, pipe.llWrite);
        
//        //there are times when result can be false but we have data which relate to our holding the position for some other reason. only when we hold back releases.
//        assert(!result || result ==  (Pipe.contentRemaining(pipe)>0) ) : result+" != "+Pipe.contentRemaining(pipe)+">0    "
//                     +pipe+"\n      hpCache:"+pipe.llWrite.llwHeadPosCache+"  writtenPos:"+pipe.llWrite.llwConfirmedWrittenPosition+"\n   "+
//                    " pending release bytes "+Pipe.releasePendingByteCount(pipe); //value llWrite.llwHeadPosCache  is not written by high level API , can not mix???
        return result;
    }

	private static <S extends MessageSchema<S>> boolean contentToLowLevelRead2(Pipe<S> pipe, long target, LowLevelAPIWritePositionCache llWrite) {
		//only does second part if the first does not pass
		return (llWrite.llwHeadPosCache > target) || contentToLowLevelReadSlow(pipe, target, llWrite);
	}

	private static <S extends MessageSchema<S>> boolean contentToLowLevelReadSlow(Pipe<S> pipe, long target, LowLevelAPIWritePositionCache llWrite) {
		return (llWrite.llwHeadPosCache = pipe.slabRingHead.headPos.get()) > target;  
	}

	public static <S extends MessageSchema<S>> long confirmLowLevelRead(Pipe<S> pipe, long size) {
	    assert(size>0) : "Must have read something.";
	    assert(Pipe.singleThreadPerPipeRead(pipe.id));
	     //not sure if this assert is true in all cases
	  //  assert(input.llWrite.llwConfirmedWrittenPosition + size <= input.slabRingHead.workingHeadPos.value+Pipe.EOF_SIZE) : "size was far too large, past known data";
	  //  assert(input.llWrite.llwConfirmedWrittenPosition + size >= input.slabRingTail.tailPos.get()) : "size was too small, under known data";   
		return (pipe.llWrite.llrConfirmedPosition += size);
	}

    public static <S extends MessageSchema<S>> void setWorkingHeadTarget(Pipe<S> pipe) {
        pipe.llWrite.llrConfirmedPosition =  Pipe.getWorkingTailPosition(pipe);
    }

    public static <S extends MessageSchema<S>> int getBlobTailPosition(Pipe<S> pipe) {
	    return PaddedInt.get(pipe.blobRingTail.bytesTailPos);
	}

    @Deprecated
	public static <S extends MessageSchema<S>> int getBlobRingTailPosition(Pipe<S> pipe) {
	    return getBlobTailPosition(pipe);
	}

	public static <S extends MessageSchema<S>> void setBytesTail(Pipe<S> pipe, int value) {
        PaddedInt.set(pipe.blobRingTail.bytesTailPos, value);
    }

    public static <S extends MessageSchema<S>> int getBlobHeadPosition(Pipe<S> pipe) {
        return PaddedInt.get(pipe.blobRingHead.bytesHeadPos);        
    }
	
    @Deprecated
    public static <S extends MessageSchema<S>> int getBlobRingHeadPosition(Pipe<S> pipe) {
        return getBlobHeadPosition(pipe); 
    }

    public static <S extends MessageSchema<S>> void setBytesHead(Pipe<S> pipe, int value) {
        PaddedInt.set(pipe.blobRingHead.bytesHeadPos, value);
    }

    public static <S extends MessageSchema<S>> int addAndGetBytesHead(Pipe<S> pipe, int inc) {
        return PaddedInt.add(pipe.blobRingHead.bytesHeadPos, inc);
    }

    public static <S extends MessageSchema<S>> int getWorkingBlobRingTailPosition(Pipe<S> pipe) {
        return PaddedInt.get(pipe.blobRingTail.byteWorkingTailPos);
    }

   public static <S extends MessageSchema<S>> int addAndGetBytesWorkingTailPosition(Pipe<S> pipe, int inc) {
        return PaddedInt.maskedAdd(pipe.blobRingTail.byteWorkingTailPos, inc, Pipe.BYTES_WRAP_MASK);
    }

    public static <S extends MessageSchema<S>> void setBytesWorkingTail(Pipe<S> pipe, int value) {
        PaddedInt.set(pipe.blobRingTail.byteWorkingTailPos, value);
    }
    
    public static <S extends MessageSchema<S>> int getWorkingBlobHeadPosition(Pipe<S> pipe) {
        return PaddedInt.get(pipe.blobRingHead.byteWorkingHeadPos);
    }

    @Deprecated
    public static <S extends MessageSchema<S>> int getBlobWorkingHeadPosition(Pipe<S> pipe) {
        return getWorkingBlobHeadPosition(pipe);
    }
    
    public static <S extends MessageSchema<S>> int addAndGetBytesWorkingHeadPosition(Pipe<S> pipe, int inc) {
    	assert(inc>=0) : "only zero or positive values supported";
        return PaddedInt.maskedAdd(pipe.blobRingHead.byteWorkingHeadPos, inc, Pipe.BYTES_WRAP_MASK);
    }

    public static <S extends MessageSchema<S>> void setBytesWorkingHead(Pipe<S> pipe, int value) {
        PaddedInt.set(pipe.blobRingHead.byteWorkingHeadPos, value);
    }

    public static <S extends MessageSchema<S>> int decBatchRelease(Pipe<S> pipe) {
        return --pipe.batchReleaseCountDown;
    }

    public static <S extends MessageSchema<S>> int decBatchPublish(Pipe<S> pipe) {
        return --pipe.batchPublishCountDown;
    }

    public static <S extends MessageSchema<S>> void beginNewReleaseBatch(Pipe<S> pipe) {
        pipe.batchReleaseCountDown = pipe.batchReleaseCountDownInit;
    }

    public static <S extends MessageSchema<S>> void beginNewPublishBatch(Pipe<S> pipe) {
        pipe.batchPublishCountDown = pipe.batchPublishCountDownInit;
    }

    public static <S extends MessageSchema<S>> byte[] blob(Pipe<S> pipe) {        
        return pipe.blobRing;
    }
    
    public static <S extends MessageSchema<S>> int[] slab(Pipe<S> pipe) {
        return pipe.slabRing;
    }
    
    @Deprecated
    public static <S extends MessageSchema<S>> byte[] byteBuffer(Pipe<S> pipe) {        
        return blob(pipe);
    }

    @Deprecated
    public static <S extends MessageSchema<S>> int[] primaryBuffer(Pipe<S> pipe) {
        return slab(pipe);
    }

    public static <S extends MessageSchema<S>> void updateBytesWriteLastConsumedPos(Pipe<S> pipe) {
        pipe.blobWriteLastConsumedPos = Pipe.getWorkingBlobHeadPosition(pipe);
    }

    public static <S extends MessageSchema<S>> PaddedLong getWorkingTailPositionObject(Pipe<S> pipe) {
        return pipe.slabRingTail.workingTailPos;
    }

    public static <S extends MessageSchema<S>> PaddedLong getWorkingHeadPositionObject(Pipe<S> pipe) {
        return pipe.slabRingHead.workingHeadPos;
    }

    public static <S extends MessageSchema<S>> int sizeOf(Pipe<S> pipe, int msgIdx) {
    	return sizeOf(pipe.schema, msgIdx);
    }
    
    public static <S extends MessageSchema<S>> int sizeOf(S schema, int msgIdx) {
        return msgIdx>=0? schema.from.fragDataSize[msgIdx] : Pipe.EOF_SIZE;
    }

    public static <S extends MessageSchema<S>> void releasePendingReadLock(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        PendingReleaseData.releasePendingReadRelease(pipe.pendingReleases, pipe);
    }
    
    public static <S extends MessageSchema<S>> void releasePendingAsReadLock(Pipe<S> pipe, int consumed) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
    	PendingReleaseData.releasePendingAsReadRelease(pipe.pendingReleases, pipe, consumed);
    }
    
    public static <S extends MessageSchema<S>> int releasePendingCount(Pipe<S> pipe) {
    	return PendingReleaseData.pendingReleaseCount(pipe.pendingReleases);
    }
    
    public static <S extends MessageSchema<S>> int releasePendingByteCount(Pipe<S> pipe) {
     	return PendingReleaseData.pendingReleaseByteCount(pipe.pendingReleases);
    }
    
    public static <S extends MessageSchema<S>> void releaseAllPendingReadLock(Pipe<S> pipe) {
    	assert(Pipe.singleThreadPerPipeRead(pipe.id));
        PendingReleaseData.releaseAllPendingReadRelease(pipe.pendingReleases, pipe);
    }

    
    /**
     * Hold this position in case we want to abandon what is written
     * @param pipe
     */
    public static void markHead(Pipe pipe) {
        pipe.markedHeadSlab = Pipe.workingHeadPosition(pipe);
        pipe.markedHeadBlob = Pipe.getWorkingBlobHeadPosition(pipe);
    }
    
    /**
     * abandon what has been written in this fragment back to the markHead position.
     * @param pipe
     */
    public static void resetHead(Pipe pipe) {
        Pipe.setWorkingHead(pipe, pipe.markedHeadSlab);
        Pipe.setBytesWorkingHead(pipe, pipe.markedHeadBlob);
    }
    
    /**
     * Hold this position in case we want to re-read this single message
     * @param pipe
     */
    public static void markTail(Pipe pipe) {
        pipe.markedTailSlab = Pipe.getWorkingTailPosition(pipe);
        pipe.markedTailBlob = Pipe.getWorkingBlobRingTailPosition(pipe);
    }
    
    /**
     * abandon what has been read and move back to top of fragment to read again.
     * MUST be called before confirm of read and never after
     * @param pipe
     */
    public static void resetTail(Pipe pipe) {
        Pipe.setWorkingTailPosition(pipe, pipe.markedTailSlab);
        Pipe.setBytesWorkingTail(pipe, pipe.markedTailBlob);
    }
    
    
	public static int storeBlobWorkingHeadPosition(Pipe<?> target) {
		assert(-1 == target.activeBlobHead) : "can not store second until first is resolved";
		return target.activeBlobHead = Pipe.getWorkingBlobHeadPosition(target);				
	}
    
	public static int unstoreBlobWorkingHeadPosition(Pipe<?> target) {
		assert(-1 != target.activeBlobHead) : "can not unstore value not saved";
		int result = target.activeBlobHead;
		target.activeBlobHead = -1;
		return result;
	}


}
