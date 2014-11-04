package com.ctriposs.tsdb.iterator;

import java.io.IOException;
import java.util.Map.Entry;

import com.ctriposs.tsdb.DBConfig;
import com.ctriposs.tsdb.InternalEntry;
import com.ctriposs.tsdb.InternalKey;
import com.ctriposs.tsdb.common.IStorage;
import com.ctriposs.tsdb.storage.CodeBlock;
import com.ctriposs.tsdb.storage.CodeItem;
import com.ctriposs.tsdb.storage.Head;
import com.ctriposs.tsdb.storage.TimeBlock;
import com.ctriposs.tsdb.storage.TimeItem;

public class FileSeekIterator implements IFileIterator<InternalKey, byte[]> {

	private IStorage storage;
	private int maxCodeBlockIndex = 0;
	private int maxTimeBlockIndex = 0;
	private int curCodeBlockIndex = -1;
	private int curTimeBlockIndex = -1;
	
	private Entry<InternalKey, byte[]> curEntry;
	private TimeBlock curTimeBlock;
	private CodeBlock curCodeBlock;
	private CodeItem curCodeItem;
	private Head head;
	
	public FileSeekIterator(IStorage storage)throws IOException {
		this.storage = storage;
		byte[] bytes = new byte[Head.HEAD_SIZE];
		this.storage.get(0, bytes);
		this.head = new Head(bytes);
		this.maxCodeBlockIndex = (head.getCodeCount() + DBConfig.BLOCK_MAX_COUNT)/DBConfig.BLOCK_MAX_COUNT - 1;
		this.curEntry = null;
		this.curTimeBlock = null;
		this.curCodeBlock = null;
		this.curCodeItem = null;
	}

	@Override
	public boolean hasNext() {
		if(curTimeBlockIndex <= maxTimeBlockIndex){
			if(curTimeBlock != null){
				if(!curTimeBlock.hasNext()){
					try{
						nextTimeBlock();
					}catch(IOException e){
						throw new RuntimeException(e);
					}
					if(curTimeBlock == null){
						return false;
					}else{
						return true;
					}
				}else{
					return true;
				}
			}else{
				try{
					nextTimeBlock();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				if(curTimeBlock == null){
					return false;
				}else{
					return true;
				}
			}
		}
		return false;
	}

	private void nextCodeBlock() throws IOException{
		++curCodeBlockIndex;
		byte[] bytes = null;
		int count = 0;
		if(curCodeBlockIndex == maxCodeBlockIndex){
			count = head.getCodeCount() - curCodeBlockIndex*DBConfig.BLOCK_MAX_COUNT;
		}else{
			count = DBConfig.BLOCK_MAX_COUNT;		
		}
		bytes = new byte[count*CodeItem.CODE_ITEM_SIZE];
		storage.get(head.getCodeOffset()+curCodeBlockIndex*DBConfig.BLOCK_MAX_COUNT*CodeItem.CODE_ITEM_SIZE, bytes);
		curCodeBlock = new CodeBlock(bytes, count);

	}
	
	private void prevCodeBlock() throws IOException{
		--curCodeBlockIndex;
		byte[] bytes = null;
		int count = 0;
		if(curCodeBlockIndex == maxCodeBlockIndex){
			count = head.getCodeCount() - curCodeBlockIndex*DBConfig.BLOCK_MAX_COUNT;
		}else if(curCodeBlockIndex>=0){
			count = DBConfig.BLOCK_MAX_COUNT;		
		}else{
			curCodeBlock = null;
			return;
		}
		bytes = new byte[count*CodeItem.CODE_ITEM_SIZE];
		storage.get(head.getCodeOffset()+curCodeBlockIndex*DBConfig.BLOCK_MAX_COUNT*CodeItem.CODE_ITEM_SIZE, bytes);
		curCodeBlock = new CodeBlock(bytes, count);

	}
	
	private void nextTimeBlock() throws IOException{
		
		++curTimeBlockIndex;
		byte[] bytes = null;
		int count = 0;
		if(curTimeBlockIndex == maxTimeBlockIndex){
			count = curCodeItem.getTimeCount() - curTimeBlockIndex*DBConfig.BLOCK_MAX_COUNT;
		}else{
			count = DBConfig.BLOCK_MAX_COUNT;		
		}
		bytes = new byte[count*TimeItem.TIME_ITEM_SIZE];
		storage.get(curCodeItem.getTimeOffSet()+curTimeBlockIndex*DBConfig.BLOCK_MAX_COUNT+TimeItem.TIME_ITEM_SIZE, bytes);
		curTimeBlock = new TimeBlock(bytes, count);
	}
	
	private void prevTimeBlock() throws IOException{
		
		--curTimeBlockIndex;
		byte[] bytes = null;
		int count = 0;
		if(curTimeBlockIndex == maxTimeBlockIndex){
			count = curCodeItem.getTimeCount() - curTimeBlockIndex*DBConfig.BLOCK_MAX_COUNT;
		}else if(curTimeBlockIndex >= 0){
			count = DBConfig.BLOCK_MAX_COUNT;		
		}else{
			curTimeBlock = null;
			return;
		}
		bytes = new byte[count*TimeItem.TIME_ITEM_SIZE];
		storage.get(curCodeItem.getTimeOffSet()+curTimeBlockIndex*DBConfig.BLOCK_MAX_COUNT+TimeItem.TIME_ITEM_SIZE, bytes);
		curTimeBlock = new TimeBlock(bytes, count);
	}

	@Override
	public void seek(int code, long time) throws IOException {

		if (head.containCode(code)) {

			nextCodeBlock();
			if (curCodeBlock != null) {
				while (!curCodeBlock.seek(code)) {
					nextCodeBlock();
					if (curCodeBlock == null) {
						break;
					}
				}
			}

			if (curCodeBlock != null) {
				curCodeItem = curCodeBlock.current();
				if (curCodeItem != null) {
					maxCodeBlockIndex = (curCodeItem.getTimeCount() + DBConfig.BLOCK_MAX_COUNT)/ DBConfig.BLOCK_MAX_COUNT - 1;
					curTimeBlockIndex = -1;
				}
			}

			// read time
			if (curCodeItem != null) {
				nextTimeBlock();
				if (curTimeBlock != null) {
					while(curTimeBlock.containTime(time)<0){
						nextTimeBlock();
						if(curTimeBlock == null){
							break;
						}
					}
					if(curTimeBlock != null){
						if(curTimeBlock.containTime(time)==0){
							curTimeBlock.seek(time);
							return;
						}
					}
					
				}
			}

		}
		curTimeBlockIndex = -1;
		maxTimeBlockIndex = 0;
	}


	@Override
	public void seekToFirst(int code) throws IOException {

		if (head.containCode(code)) {
			nextCodeBlock();
			if (curCodeBlock != null) {
				while (!curCodeBlock.seek(code)) {
					nextCodeBlock();
					if (curCodeBlock == null) {
						break;
					}
				}
			}

			if (curCodeBlock != null) {
				curCodeItem = curCodeBlock.current();
				if (curCodeItem != null) {
					maxCodeBlockIndex = (curCodeItem.getTimeCount() + DBConfig.BLOCK_MAX_COUNT)/ DBConfig.BLOCK_MAX_COUNT - 1;
					curTimeBlockIndex = -1;
				}
			}
			
			// read time
			if (curCodeItem != null) {
				nextTimeBlock();
				if (curTimeBlock != null) {
					return;
				}
			}
		}
		curTimeBlockIndex = -1;
		maxTimeBlockIndex = 0;
	}


	@Override
	public InternalKey key() {

		if(curEntry != null){
			return curEntry.getKey();
		}
		return null;
	}


	@Override
	public long time() {

		if(curEntry != null){
			return curEntry.getKey().getTime();
		}
		return 0;
	}

	@Override
	public byte[] value() throws IOException {

		if(curEntry != null){
			return curEntry.getValue();
		}
		return null;
	}

	@Override
	public boolean valid() {
		if(curEntry != null){
			return true;
		}
		return false;
	}

	private void readEntry(int code,TimeItem tItem,boolean isNext) throws IOException{
		if(tItem == null){
			if(isNext){
				curTimeBlockIndex = maxTimeBlockIndex + 1;
			}else{
				curTimeBlockIndex = -1;
			}
		}else{
			InternalKey key = new InternalKey(code, tItem.getTime());
			byte[] value = new byte[tItem.getValueSize()];
			storage.get(tItem.getValueOffset(), value);
			curEntry = new InternalEntry(key, value);
		}
	}

	@Override
	public Entry<InternalKey, byte[]> next() {
		if(curTimeBlock == null){
			try{
				nextTimeBlock();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		if(curTimeBlock != null){
			try{
				readEntry(curCodeItem.getCode(),curTimeBlock.next(),true);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}else{
			curEntry = null;
		}
			
		return curEntry;
	}

	@Override
	public Entry<InternalKey, byte[]> prev() {
		if(curTimeBlock == null){
			try{
				prevTimeBlock();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		if(curTimeBlock != null){
			try{
				readEntry(curCodeItem.getCode(),curTimeBlock.next(),false);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}else{
			curEntry = null;
		}
			
		return curEntry;
	}

	@Override
	public CodeItem nextCode() throws IOException {
		if(curCodeBlock == null){
			nextCodeBlock();
		}
		if(curCodeBlock != null){
			return curCodeBlock.next();
		}
		return null;
	}



	@Override
	public CodeItem prevCode() throws IOException {
		if(curCodeBlock == null){
			prevCodeBlock();
		}
		if(curCodeBlock != null){
			return curCodeBlock.prev();
		}

		return null;
	}

	@Override
	public void close() throws IOException {

		storage.close();
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException("unsupport remove operation!");
	}

}
