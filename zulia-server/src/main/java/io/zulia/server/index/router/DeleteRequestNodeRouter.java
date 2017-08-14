package io.zulia.server.index.router;

import io.zulia.message.ZuliaBase.MasterSlaveSettings;
import io.zulia.message.ZuliaBase.Node;
import io.zulia.message.ZuliaServiceOuterClass.DeleteRequest;
import io.zulia.message.ZuliaServiceOuterClass.DeleteResponse;
import io.zulia.server.connection.client.InternalClient;
import io.zulia.server.index.ZuliaIndex;

import java.io.IOException;
import java.util.Collection;

public class DeleteRequestNodeRouter extends RequestNodeRouter<DeleteRequest, DeleteResponse> {
	private final InternalClient internalClient;
	private final ZuliaIndex index;

	public DeleteRequestNodeRouter(Node thisNode, Collection<Node> otherNodesActive, MasterSlaveSettings masterSlaveSettings, ZuliaIndex index, String uniqueId,
			InternalClient internalClient) throws IOException {
		super(thisNode, otherNodesActive, masterSlaveSettings, index, uniqueId);
		this.internalClient = internalClient;
		this.index = index;
	}

	@Override
	protected DeleteResponse processExternal(Node node, DeleteRequest request) throws Exception {
		return internalClient.executeDelete(node, request);
	}

	@Override
	protected DeleteResponse processInternal(DeleteRequest request) throws Exception {
		return internalDelete(index, request);
	}

	public static DeleteResponse internalDelete(ZuliaIndex index, DeleteRequest request) throws Exception {
		return index.deleteDocument(request);
	}
}
