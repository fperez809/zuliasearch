package io.zulia.server.connection.server.handler;

import io.zulia.message.ZuliaServiceOuterClass.FetchRequest;
import io.zulia.message.ZuliaServiceOuterClass.FetchResponse;
import io.zulia.server.index.ZuliaIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FetchServerRequest extends ServerRequestHandler<FetchResponse, FetchRequest> {

	private final static Logger LOG = LoggerFactory.getLogger(FetchServerRequest.class);

	public FetchServerRequest(ZuliaIndexManager indexManager) {
		super(indexManager);
	}

	@Override
	protected FetchResponse handleCall(ZuliaIndexManager indexManager, FetchRequest request) throws Exception {
		return indexManager.fetch(request);
	}

	@Override
	protected void onError(Throwable e) {
		LOG.error("Failed to handle fetch", e);
	}
}
