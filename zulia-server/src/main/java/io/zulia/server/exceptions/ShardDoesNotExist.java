package io.zulia.server.exceptions;

import java.io.IOException;

public class ShardDoesNotExist extends IOException {

	private static final long serialVersionUID = 1L;
	private String indexName;
	private int shardNumber;

	public ShardDoesNotExist(String indexName, int shardNumber) {
		this.indexName = indexName;
		this.shardNumber = shardNumber;
	}

	public String getIndexName() {
		return indexName;
	}

	public int getShardNumber() {
		return shardNumber;
	}
}
