package com.ctriposs.tsdb.iterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import com.ctriposs.tsdb.ISeekIterator;
import com.ctriposs.tsdb.InternalKey;
import com.ctriposs.tsdb.manage.FileManager;
import com.ctriposs.tsdb.manage.NameManager;
import com.ctriposs.tsdb.storage.FileMeta;
import com.ctriposs.tsdb.storage.PureFileStorage;
import com.ctriposs.tsdb.table.InternalKeyComparator;
import com.ctriposs.tsdb.table.MemTable;

public class SeekIteratorAdapter implements ISeekIterator<InternalKey, byte[]>{
	
	private FileManager fileManager;
	private NameManager nameManager;
	private List<IInternalSeekIterator<InternalKey, byte[]>> iterators;
	private Direction direction;
	private InternalKeyComparator internalKeyComparator;
	private Entry<InternalKey, byte[]> curEntry;
	private IInternalSeekIterator<InternalKey, byte[]> curIterator;
	private long curSeekTime;
	private InternalKey seekKey;
	
	public SeekIteratorAdapter(FileManager fileManager,NameManager nameManager,InternalKeyComparator internalKeyComparator){
		this.fileManager = fileManager;
		this.nameManager = nameManager;
		this.curEntry = null;
		this.direction = Direction.forward;
		this.internalKeyComparator = internalKeyComparator;
		this.curIterator = null;
		this.iterators = null;
	}

	@Override
	public boolean hasNext() {
		boolean result = false;
		for (IInternalSeekIterator<InternalKey, byte[]> it : iterators) {
			if(it.hasNext()){
				result = true; 
			}
		}
		if(!result){
			curSeekTime += MemTable.MINUTE;
			if(curSeekTime < System.currentTimeMillis()){
				try {
					iterators = getNextIterators(curSeekTime);
					if(null != iterators){
						for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
							it.seek(seekKey.getCode());
						}		
						findSmallest();
						direction = Direction.forward;
					}
				} catch (IOException e) {
					e.printStackTrace();
					result = false;
				}
			}else{
				result = false;
			}
		}
		return result;
	}

	@Override
	public Entry<InternalKey, byte[]> next() {
		if (direction != Direction.forward) {
			for (IInternalSeekIterator<InternalKey, byte[]> it : iterators) {

				if (it != curIterator) {
					try {
						if (it.hasNext()) {
							it.seek(seekKey.getCode());
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			direction = Direction.forward;
		}
		curEntry = curIterator.next();
		findSmallest();
		return curEntry;
	}
	

	@Override
	public Entry<InternalKey, byte[]> prev() {
		if(direction != Direction.reverse){
			for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
				if(curIterator != it){
					try {
						if(it.hasNext()){
							it.seek(seekKey.getCode());
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			direction = Direction.reverse;
		}
		curEntry = curIterator.prev();
		findLargest();
		return curEntry;		
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException("unsupport remove operation!");
	}
	
	private long format(long time){
		return time/MemTable.MINUTE*MemTable.MINUTE;
	}
	
	@Override
	public void seek(String table, String column, long time) throws IOException {
		seekKey = new InternalKey(nameManager.getCode(table),nameManager.getCode(column),time);
		
		iterators = getNextIterators(format(time));
		
		if(null != iterators){
			for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
				it.seek(seekKey.getCode());
			}		
			findSmallest();
			direction = Direction.forward;
		}
	}
	
	private void findSmallest(){
		if(null != iterators){
			IInternalSeekIterator<InternalKey, byte[]> smallest = null;
			for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
				if(it.hasNext()){
					if(smallest == null){
						smallest = it;
					}else if(internalKeyComparator.compare(smallest.key(), it.key())>0){
						smallest = it;
					}
				}
			}
			curIterator = smallest;
		}
	}
	
	private void findLargest(){
		if(null != iterators){
			IInternalSeekIterator<InternalKey, byte[]> largest = null;
			for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
				if(it.hasNext()){
					if(largest == null){
						largest = it;
					}else if(internalKeyComparator.compare(largest.key(), it.key())<0){
						largest = it;
					}
				}
			}
			curIterator = largest;
		}
	}
	
	private List<IInternalSeekIterator<InternalKey, byte[]>> getNextIterators(long time) throws IOException{
		if(time > System.currentTimeMillis()){
			return null;
		}
		curSeekTime = time;
		List<FileMeta> metas = fileManager.getFiles(time);
		if(metas != null){
			List<IInternalSeekIterator<InternalKey, byte[]>> list = new ArrayList<IInternalSeekIterator<InternalKey, byte[]>>();
			for(FileMeta meta:metas){
				list.add(new FileSeekInterator(new PureFileStorage(meta.getFile(), meta.getFile().length()), nameManager));
			}
			return list;
		}else{
			return getNextIterators(time+MemTable.MINUTE);
		}
	}
	

	@Override
	public String table() {
		if(curEntry != null){
			nameManager.getName(curEntry.getKey().getTableCode());
		}
		return null;
	}

	@Override
	public String column() {
		if(curEntry != null){
			nameManager.getName(curEntry.getKey().getColumnCode());
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
		if(curEntry==null){
			return false;
		}else{
			return true;
		}
	}


	@Override
	public void close() throws IOException{
		
		if(null != iterators){
			for(IInternalSeekIterator<InternalKey, byte[]> it:iterators){
				it.close();
			}
		}
	}

	@Override
	public InternalKey key() {
		if(curEntry != null){
			return curEntry.getKey();
		}
		return null;
	}
	
	enum Direction{
		forward,reverse
	}
}