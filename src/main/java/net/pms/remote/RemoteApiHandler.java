package net.pms.remote;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.configuration.WebRender;
import net.pms.dlna.CodeEnter;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.Playlist;
import net.pms.dlna.RootFolder;
import net.pms.dlna.virtual.VirtualVideoAction;
import net.pms.util.UMSUtils;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RemoteApiHandler implements HttpHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemoteBrowseHandler.class);
	private RemoteWeb parent;
	private static PmsConfiguration configuration = PMS.getConfiguration();

	public RemoteApiHandler(RemoteWeb parent) {
		this.parent = parent;
	}
	
	private String mkApiPage(String id, HttpExchange t) throws IOException {
		String user = RemoteUtil.userName(t);
		RootFolder root = parent.getRoot(user, true, t);
		String search = RemoteUtil.getQueryVars(t.getRequestURI().getQuery(), "str");

		List<DLNAResource> res = root.getDLNAResources(id, true, 0, 0, root.getDefaultRenderer(), search);
		boolean upnpAllowed = RemoteUtil.bumpAllowed(t);
		boolean upnpControl = RendererConfiguration.hasConnectedControlPlayers();
		if (!res.isEmpty() &&
			res.get(0).getParent() != null &&
			(res.get(0).getParent() instanceof CodeEnter)) {
			// this is a code folder the search string is  entered code
			CodeEnter ce = (CodeEnter)res.get(0).getParent();
			ce.setEnteredCode(search);
			if(!ce.validCode(ce)) {
				// invalid code throw error
				throw new IOException("Auth error");
			}
			DLNAResource real = ce.getResource();
			if (!real.isFolder()) {
				// no folder   -> redirect
				Headers hdr = t.getResponseHeaders();
				hdr.add("Location", "/play/" + real.getId());
				RemoteUtil.respond(t, "", 302, "text/html");
				// return null here to avoid multipl responses
				return null;
			}
			else {
				// redirect to ourself
				Headers hdr = t.getResponseHeaders();
				hdr.add("Location", "/browse/" + real.getResourceId());
				RemoteUtil.respond(t, "", 302, "text/html");
				return null;
			}
		}
		if (StringUtils.isNotEmpty(search) && !(res instanceof CodeEnter)) {
			UMSUtils.postSearch(res, search);
		}

		JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
		ArrayNode folders = nodeFactory.arrayNode();
		ArrayNode media = nodeFactory.arrayNode();

		// Generate innerHtml snippets for folders and media items
		for (DLNAResource r : res) {
			ObjectNode node = nodeFactory.objectNode();
			String newId = r.getResourceId();
			String idForWeb = URLEncoder.encode(newId, "UTF-8");
			String thumb = "/thumb/" + idForWeb;
			String name = StringEscapeUtils.escapeHtml(r.resumeName());
			
			node.put("id", idForWeb);
			node.put("name", name);
			node.put("thumbnail", thumb);

			if (r instanceof VirtualVideoAction) {
				node.put("media", "/raw/" + idForWeb);
				node.put("enabled", true);
				media.add(node);
				continue;
			}

			if (r.isFolder()) {
				node.put("path", "/api/" + idForWeb);
				folders.add(node);
			} else {
				if (WebRender.supports(r) || r.isResume()) {
					node.put("media", "/raw/" + idForWeb);
					node.put("enabled", true);
				} else if (upnpControl && upnpAllowed) {
					node.put("enabled", false);
				}
				media.add(node);
			}
		}
		
		ObjectNode vars = nodeFactory.objectNode();
		if(id.equals("0")) {
			vars.put("name", configuration.getServerName());			
		} else {
			DLNAResource parentRes = root.getDLNAResource(id, null);
			vars.put("name", StringEscapeUtils.escapeHtml(parentRes.getDisplayName()));
			vars.put("parent", "/api/" + URLEncoder.encode(parentRes.getParent().getResourceId(), "UTF-8"));
		}
		vars.put("folders", folders);
		vars.put("media", media);
		vars.put("push", configuration.useWebControl());

		return new ObjectMapper().writeValueAsString(vars);
	}

	@Override
	public void handle(HttpExchange t) throws IOException {
		if (RemoteUtil.deny(t)) {
			throw new IOException("Access denied");
		}
		String id = RemoteUtil.getId("api/", t);
		LOGGER.debug("Got an api request found id " + id);
		String response = mkApiPage(id, t);
		LOGGER.debug("Write page " + response);
		RemoteUtil.respond(t, response, 200, "application/json");
	}

}
