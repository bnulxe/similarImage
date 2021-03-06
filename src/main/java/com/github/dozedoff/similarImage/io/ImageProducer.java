/*  Copyright (C) 2013  Nicholas Wright
    
    This file is part of similarImage - A similar image finder using pHash
    
    mmut is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.dozedoff.similarImage.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dozedoff.similarImage.db.Persistence;
import com.github.dozedoff.similarImage.hash.PhashWorker;
import com.github.dozedoff.similarImage.thread.ImageLoadJob;
import com.github.dozedoff.similarImage.thread.NamedThreadFactory;
import com.google.common.collect.Lists;

public class ImageProducer {
	private static final Logger logger = LoggerFactory.getLogger(ImageProducer.class);
	private final Persistence persistence;
	private final AtomicInteger total = new AtomicInteger();
	private final AtomicInteger processed = new AtomicInteger();

	private final int WORK_BATCH_SIZE = 10;

	private LinkedList<ImageProducerObserver> guiUpdateListeners;

	private LinkedBlockingQueue<Runnable> jobQueue;
	private ThreadPoolExecutor tpe;
	private PhashWorker phw;
	private int poolSize = 2;

	public ImageProducer(Persistence persistence, PhashWorker phw) {
		guiUpdateListeners = new LinkedList<>();

		this.persistence = persistence;
		this.jobQueue = new LinkedBlockingQueue<>();
		this.phw = phw;
		this.tpe = new ImageProducerPool(poolSize, poolSize, 10, TimeUnit.SECONDS, jobQueue, new NamedThreadFactory(
				ImageProducer.class.getSimpleName()), this);
		this.tpe.allowCoreThreadTimeOut(true);
	}

	public void setPoolSize(int poolSize) {
		if (poolSize < 1) {
			logger.error("Pool size must be 1 or greater, {} is not valid and ignored", poolSize);
			return;
		}

		this.poolSize = poolSize;
		this.tpe.setCorePoolSize(poolSize);
		this.tpe.setMaximumPoolSize(poolSize);
	}

	public int getPoolSize() {
		return poolSize;
	}

	public ImageProducer(Persistence persistence, PhashWorker phw, ThreadPoolExecutor customTpe) {
		this(persistence, phw);
		this.tpe = customTpe;
	}

	public void addToLoad(List<Path> paths) {
		total.addAndGet(paths.size());
		createJobBatches(paths);

		listenersUpdateTotalProgress();
	}

	public void addToLoad(Path... paths) {
		addToLoad(Lists.newArrayList(paths));
	}

	private void createJobBatches(List<Path> paths) {
		int counter = 0;
		ArrayList<Path> batch = new ArrayList<>(WORK_BATCH_SIZE);

		for (Path p : paths) {
			batch.add(p);
			counter++;

			if (counter >= WORK_BATCH_SIZE) {
				createJob(batch);

				counter = 0;
				batch = new ArrayList<>(WORK_BATCH_SIZE); // don't use .clear
			}
		}

		createJob(batch);
	}

	private void createJob(List<Path> batch) {
		ImageLoadJob job = new ImageLoadJob(batch, phw, persistence);
		tpe.execute(job);
	}

	public void clear() {
		jobQueue.clear();
		processed.set(0);
		total.set(0);

		listenersUpdateTotalProgress();
		logger.info("Job queue cleared");
	}

	public void forceShutdown() {
		tpe.shutdownNow();
	}

	@Deprecated
	public void shutdown() {
		forceShutdown();
	}

	public void gracefulShutdown() {
		tpe.shutdown();
	}

	public boolean isTerminated() {
		return tpe.isTerminated();
	}

	public int getTotal() {
		return total.get();
	}

	public int getProcessed() {
		return processed.get();
	}

	public void addGuiUpdateListener(ImageProducerObserver listener) {
		this.guiUpdateListeners.add(listener);
	}

	public void removeGuiUpdateListener(ImageProducerObserver listener) {
		this.guiUpdateListeners.remove(listener);
	}

	void listenersUpdateTotalProgress() {
		for (ImageProducerObserver o : guiUpdateListeners) {
			o.totalProgressChanged(processed.get(), total.get());
		}
	}

	void addToProcessed(int done) {
		processed.addAndGet(done);
	}
}
