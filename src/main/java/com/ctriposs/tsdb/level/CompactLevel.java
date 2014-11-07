package com.ctriposs.tsdb.level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import com.ctriposs.tsdb.InternalKey;
import com.ctriposs.tsdb.common.Level;
import com.ctriposs.tsdb.common.PureFileStorage;
import com.ctriposs.tsdb.iterator.FileSeekIterator;
import com.ctriposs.tsdb.iterator.MergeFileSeekIterator;
import com.ctriposs.tsdb.manage.FileManager;
import com.ctriposs.tsdb.storage.DBWriter;
import com.ctriposs.tsdb.storage.FileMeta;
import com.ctriposs.tsdb.storage.FileName;
import com.ctriposs.tsdb.util.FileUtil;

public class CompactLevel extends Level {

	public final static long MAX_PERIOD = 1000 * 60 * 60 * 24 * 30L;
    public final static long ONE_HOUR = 1000 * 60 * 60L;

	private AtomicLong storeCounter = new AtomicLong(0);
	private AtomicLong storeErrorCounter = new AtomicLong(0);
	private Level prevLevel;

	public CompactLevel(FileManager fileManager, Level prevLevel, int level, long interval, int threads) {
		super(fileManager, level, interval, threads);
		this.prevLevel = prevLevel;

        for (int i = 0; i < threads; i++) {
            tasks[i] = new CompactTask(i);
        }
	}

	public long getStoreCounter(){
		return storeCounter.get();
	}
	
	public long getStoreErrorCounter(){
		return storeErrorCounter.get();
	}

	@Override
	public void incrementStoreError() {
		storeErrorCounter.incrementAndGet();
	}

	@Override
	public void incrementStoreCount() {
		storeCounter.incrementAndGet();
	}

	class CompactTask extends Task {

		public CompactTask(int num) {
			super(num);
			
		}

		@Override
		public byte[] getValue(InternalKey key) {
			return null;
		}

		@Override
		public void process() throws Exception {
            System.out.println("Start running level " + level + " merge thread at " + System.currentTimeMillis());
            System.out.println("Current hash map size at level " + level + " is " + timeFileMap.size());

            Map<Long, List<Long>> levelMap = new HashMap<Long, List<Long>>();
            long compactFlag = format(System.currentTimeMillis() - 60 * prevLevel.getLevelInterval(), prevLevel.getLevelInterval());
            ConcurrentNavigableMap<Long, ConcurrentSkipListSet<FileMeta>> headMap = prevLevel.getTimeFileMap().headMap(compactFlag);
            NavigableSet<Long> keySet = headMap.keySet();

            for (Long time : keySet) {
                long ts = format(time, interval);
                if (ts % tasks.length == num) {
                    List<Long> timeList = levelMap.get(ts);

                    if (timeList == null) {
                        timeList = new ArrayList<Long>();
                        levelMap.put(ts, timeList);
                    }
                    timeList.add(time);
                }
            }

            for (Map.Entry<Long, List<Long>> entry : levelMap.entrySet()) {
                long higherLevelKey = entry.getKey();
                List<FileMeta> fileMetaList = new ArrayList<FileMeta>();
                for (Long time : entry.getValue()) {
                    fileMetaList.addAll(prevLevel.getFiles(time));
                }

                FileMeta newFileMeta = mergeSort(higherLevelKey, fileMetaList);

                for (FileMeta fileMeta : fileMetaList) {
                	
                    FileUtil.forceDelete(fileMeta.getFile());
                }

                // add this to the current level
            }
		}

        private FileMeta mergeSort(long time, List<FileMeta> fileMetaList) throws IOException {
        	MergeFileSeekIterator mergeIterator = new MergeFileSeekIterator(fileManager);
            long totalTimeCount = 0;
            long fileLen = 0;
            for (FileMeta meta : fileMetaList) {
                FileSeekIterator fileIterator = new FileSeekIterator(new PureFileStorage(meta.getFile()));
                mergeIterator.addIterator(fileIterator);
                totalTimeCount += fileIterator.timeItemCount();
                fileLen += meta.getFile().length();
            }

            long fileNumber = fileManager.getFileNumber();
            PureFileStorage fileStorage = new PureFileStorage(fileManager.getStoreDir(), time, FileName.dataFileName(fileManager.getFileNumber(), level), fileLen);
            DBWriter dbWriter = new DBWriter(fileStorage, totalTimeCount, fileNumber);
            while (mergeIterator.hasNext()) {
                Map.Entry<InternalKey, byte[]> entry = mergeIterator.next();
                dbWriter.add(entry.getKey(), entry.getValue());
            }

            return dbWriter.close();
        }
	}

	@Override
	public byte[] getValue(InternalKey key) throws IOException {
		return getValueFromFile(key);
	}

}
