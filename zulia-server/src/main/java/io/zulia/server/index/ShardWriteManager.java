package io.zulia.server.index;

import io.zulia.ZuliaFieldConstants;
import io.zulia.server.analysis.ZuliaPerFieldAnalyzer;
import io.zulia.server.config.ServerIndexConfig;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.facet.taxonomy.writercache.LruTaxonomyWriterCache;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NRTCachingDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public class ShardWriteManager {

	private final static Logger LOG = LoggerFactory.getLogger(ShardWriteManager.class);
	private final ZuliaPerFieldAnalyzer zuliaPerFieldAnalyzer;
	private final ShardDocumentIndexer shardDocumentIndexer;
	private final ServerIndexConfig indexConfig;
	private final int shardNumber;
	private final String indexName;
	private final AtomicLong counter;
	private Long lastCommit;
	private Long lastChange;
	private Long lastWarm;
	private IndexWriter indexWriter;
	private DirectoryTaxonomyWriter taxoWriter;

	public ShardWriteManager(int shardNumber, Path pathToIndex, Path pathToTaxoIndex, ServerIndexConfig indexConfig,
			ZuliaPerFieldAnalyzer zuliaPerFieldAnalyzer) throws IOException {

		this.shardNumber = shardNumber;
		this.zuliaPerFieldAnalyzer = zuliaPerFieldAnalyzer;
		this.indexConfig = indexConfig;
		this.indexName = indexConfig.getIndexName();

		this.shardDocumentIndexer = new ShardDocumentIndexer(indexConfig);

		this.counter = new AtomicLong();
		this.lastCommit = null;
		this.lastChange = null;
		this.lastWarm = null;

		openIndexWriter(pathToIndex);
		openTaxoWriter(pathToTaxoIndex);

		updateIndexSettings();

	}

	public int getShardNumber() {
		return shardNumber;
	}

	public ServerIndexConfig getIndexConfig() {
		return indexConfig;
	}

	private void openIndexWriter(Path pathToIndex) throws IOException {

		Directory d = MMapDirectory.open(pathToIndex);

		IndexWriterConfig config = new IndexWriterConfig(zuliaPerFieldAnalyzer);
		config.setIndexDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
		config.setMaxBufferedDocs(Integer.MAX_VALUE);
		config.setRAMBufferSizeMB(128); // should be overwritten by ZuliaShard.updateIndexSettings()
		config.setUseCompoundFile(false);

		NRTCachingDirectory nrtCachingDirectory = new NRTCachingDirectory(d, 50, 150);

		this.indexWriter = new IndexWriter(nrtCachingDirectory, config);

	}

	private void openTaxoWriter(Path pathToTaxo) throws IOException {
		Directory d = MMapDirectory.open(pathToTaxo);
		NRTCachingDirectory nrtCachingDirectory = new NRTCachingDirectory(d, 5, 15);
		this.taxoWriter = new DirectoryTaxonomyWriter(nrtCachingDirectory, IndexWriterConfig.OpenMode.CREATE_OR_APPEND,
				new LruTaxonomyWriterCache(32 * 1024 * 1024));

	}

	public void close() throws IOException {
		if (indexWriter != null) {
			Directory directory = indexWriter.getDirectory();

			indexWriter.close();
			indexWriter = null;
			directory.close();

		}
		if (taxoWriter != null) {
			Directory directory = taxoWriter.getDirectory();

			taxoWriter.close();

			taxoWriter = null;

			directory.close();

		}
	}

	public ShardReader createShardReader() throws IOException {
		DirectoryReader indexReader = DirectoryReader.open(indexWriter);
		DirectoryTaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoWriter);
		taxoReader.setCacheSize(128000);
		return new ShardReader(shardNumber, indexReader, taxoReader, indexConfig, zuliaPerFieldAnalyzer);
	}

	public void commit() throws IOException {
		LOG.info("Committing shard <" + shardNumber + "> for index <" + indexName + ">");

		long currentTime = System.currentTimeMillis();
		indexWriter.commit();
		taxoWriter.commit();

		lastCommit = currentTime;
	}

	public boolean needsIdleCommit() {
		long currentTime = System.currentTimeMillis();

		long msIdleWithoutCommit = indexConfig.getIndexSettings().getIdleTimeWithoutCommit() * 1000L;

		//reassign so it can't change in the middle of the logic
		Long lastChange = this.lastChange;
		Long lastCommit = this.lastCommit;

		if (lastChange != null) { // if there has been a change
			long timeSinceLastChange = currentTime - lastChange;
			if (timeSinceLastChange > msIdleWithoutCommit) {
				//if there has never been a commit or the last change is after the last commit
				return (lastCommit == null) || (lastChange > lastCommit);
			}
		}
		return false;
	}

	public boolean needsSearchWarming() {
		long currentTime = System.currentTimeMillis();

		long msAfterCommitToWarm = indexConfig.getIndexSettings().getIdleTimeWithoutCommit() * 1000L;

		//reassign so it can't change in the middle of the logic
		Long lastChange = this.lastChange;
		Long lastCommit = this.lastCommit;
		Long lastWarm = this.lastWarm;

		if (lastWarm == null) {
			return true;
		}

		if (lastCommit != null && lastChange != null) { // if there has been a change to the index and a commit
			if (lastChange < lastCommit) { // no changes since last commit
				long timeSinceLastCommit = currentTime - lastCommit;
				if (timeSinceLastCommit > msAfterCommitToWarm) {
					//if the last commit is after the last warming
					return lastCommit > lastWarm;
				}
			}
		}

		return false;
	}

	public void searchesWarmed() {
		lastWarm = System.currentTimeMillis();
	}

	public boolean markedChangedCheckIfCommitNeeded() {
		lastChange = System.currentTimeMillis();

		long count = counter.incrementAndGet();
		return (count % indexConfig.getIndexSettings().getShardCommitInterval()) == 0;

	}

	public void updateIndexSettings() {
		int ramBufferMB = indexConfig.getRAMBufferMB() != 0 ? indexConfig.getRAMBufferMB() : 128;
		indexWriter.getConfig().setRAMBufferSizeMB(ramBufferMB);
		lastWarm = null;
	}

	public void deleteDocuments(String uniqueId) throws IOException {
		Term term = new Term(ZuliaFieldConstants.ID_FIELD, uniqueId);
		indexWriter.deleteDocuments(term);
	}

	public void forceMerge(int maxNumberSegments) throws IOException {
		indexWriter.forceMerge(maxNumberSegments);

	}

	public void deleteAll() throws IOException {
		indexWriter.deleteAll();
	}

	public void indexDocument(String uniqueId, long timestamp, DocumentContainer mongoDocument, DocumentContainer metadata) throws Exception {
		Document luceneDocument = shardDocumentIndexer.getIndexDocument(uniqueId, timestamp, mongoDocument, metadata, taxoWriter);
		Term updateQuery = new Term(ZuliaFieldConstants.ID_FIELD, uniqueId);
		indexWriter.updateDocument(updateQuery, luceneDocument);

	}

}
