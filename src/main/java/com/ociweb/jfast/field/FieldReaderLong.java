package com.ociweb.jfast.field;

import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.stream.DictionaryFactory;

public class FieldReaderLong {
	
	//crazy big value? TODO: make smaller mask based on exact length of array.
	private final int INSTANCE_MASK = 0xFFFFF;//20 BITS
	
	
	private final PrimitiveReader reader;
	
	private final long[]  lastValue;


	public FieldReaderLong(PrimitiveReader reader, long[] values) {
		this.reader = reader;
		this.lastValue = values;
	}
	
	public void reset(DictionaryFactory df) {
		df.reset(lastValue);
	}

	public long readLongUnsigned(int token) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned();
	}

	public long readLongUnsignedOptional(int token, long valueOfOptional) {
		long value = reader.readLongUnsigned();
		if (0==value) {
			return valueOfOptional;
		} else {
			return --value;
		}
	}

	public long readLongUnsignedConstant(int token) {
		int idx = token & INSTANCE_MASK;
		return (reader.popPMapBit()==0 ? 
					lastValue[idx]:
					reader.readLongUnsigned()	
				);
	}

	public long readLongUnsignedCopy(int token) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned()));
	}

	public long readLongUnsignedCopyOptional(int token, long valueOfOptional) {
		//if zero then use old value.
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()==0) {
			return (lastValue[idx] == 0 ? valueOfOptional: lastValue[idx]-1);
		} else {
			long value = lastValue[idx] = reader.readLongUnsigned();
			if (0==value) {
				return valueOfOptional;
			} else {
				return value-1;
			}
		}
	}
	
	
	public long readLongUnsignedDelta(int token) {
		
		int index = token & INSTANCE_MASK;
		return (lastValue[index] = (lastValue[index]+reader.readLongSigned()));
		
	}
	
	public long readLongUnsignedDeltaOptional(int token, long valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		if (reader.popPMapBit()==0) {
			long result = lastValue[instance];
			return (result == 0 ? valueOfOptional: result);
		} else {
			//1 in pmap so sending delta or non value
			long value = reader.readLongSigned();
			if (0==value) {
				lastValue[instance]=0;
				return valueOfOptional;
			} else {
				return lastValue[instance] += (value-1);
				
			}
		}
	}

	public long readLongUnsignedDefault(int token) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readLongUnsigned();
		}
	}

	public long readLongUnsignedDefaultOptional(int token, long valueOfOptional) {
		if (reader.popPMapBit()==0) {
			
			int idx = token & INSTANCE_MASK;
			if (lastValue[idx] == 0) {
				//default value is null so return optional.
				return valueOfOptional;
			} else {
				//default value 
				return lastValue[idx];
			}
			
		} else {
			long value = reader.readLongUnsigned();
			if (value==0) {
				return valueOfOptional;
			} else {
				return value-1;
			}
		}
	}

	public long readLongUnsignedIncrement(int token) {
		
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[token & INSTANCE_MASK];
		} else {
			//assign and return new value
			return lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned();
		}
	}


	public long readLongUnsignedIncrementOptional(int token, long valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? valueOfOptional: ++lastValue[instance]);
		} else {
			long value = reader.readLongUnsigned();
			if (value==0) {
				lastValue[instance] = 0;
				return valueOfOptional;
			} else {
				return (lastValue[instance] = value)-1;
			}
		}
	}

	//////////////
	//////////////
	//////////////
	
	public long readLongSigned(int token) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readLongSigned();
	}

	public long readLongSignedOptional(int token, long valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		
		long value = reader.readLongSigned();
		lastValue[instance] = value;//needed for dynamic read behavior.
		if (0==value) {
			return valueOfOptional;
		} else {
			return value-1;
		}
	}

	public long readLongSignedConstant(int token) {
		int idx = token & INSTANCE_MASK;
		return (reader.popPMapBit()==0 ? 
					lastValue[idx]:
					reader.readLongSigned()	
				);
	}

	public long readLongSignedCopy(int token) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readLongSigned()));
	}

	public long readLongSignedCopyOptional(int token, long valueOfOptional) {
		//if zero then use old value.
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()==0) {
			return (lastValue[idx] == 0 ? valueOfOptional: lastValue[idx]-1);
		} else {
			long value = reader.readLongSigned();
			lastValue[idx] = value;
			if (0==value) {
				return valueOfOptional;
			} else {
				return (value>0 ? value-1 : value);
			}
		}
	}
	
	
	public long readLongSignedDelta(int token) {
		
		int index = token & INSTANCE_MASK;
		return (lastValue[index] = (lastValue[index]+reader.readLongSigned()));
		
	}
	
	public long readLongSignedDeltaOptional(int token, long valueOfOptional) {
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()==0) {
			long result = lastValue[idx];
			return (result == 0 ? valueOfOptional: result-1);
		} else {
			long value = reader.readLongSigned();
			if (0==value) {
				lastValue[idx]=0;
				return valueOfOptional;
			} else {
				return lastValue[idx] += (value-1);
			}
		}
	}

	public long readLongSignedDefault(int token) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readLongSigned();
		}
	}

	public long readLongSignedDefaultOptional(int token, long valueOfOptional) {
		if (reader.popPMapBit()==0) {
			
			int idx = token & INSTANCE_MASK;
			if (lastValue[idx] == 0) {
				//default value is null so return optional.
				return valueOfOptional;
			} else {
				//default value 
				return lastValue[idx];
			}
			
		} else {
			long value = reader.readLongSigned();
			if (value==0) {
				return valueOfOptional;
			} else {
				return --value;
			}
		}
	}

	public long readLongSignedIncrement(int token) {
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[idx];
		} else {
			//assign and return new value
			return lastValue[idx] = reader.readLongSigned();
		}
	}


	public long readLongSignedIncrementOptional(int token, long valueOfOptional) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? valueOfOptional: ++lastValue[instance]);
		} else {
			long value = reader.readLongSigned();
			if (value==0) {
				lastValue[instance] = 0;
				return valueOfOptional;
			} else {
				return value>0 ? (lastValue[instance] = value)-1 : (lastValue[instance] = value);
			}
		}
		
	}
	
	
}
