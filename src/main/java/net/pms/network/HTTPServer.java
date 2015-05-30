/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.network;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTPServer implements Runnable {
	private static final Logger LOGGER = LoggerFactory.getLogger(HTTPServer.class);
	private static final PmsConfiguration configuration = PMS.getConfiguration();
	private final int port;
	private String hostname;
	private ServerSocketChannel serverSocketChannel;
	private ServerSocket serverSocket;
	private boolean stop;
	private Thread runnable;
	private InetAddress iafinal;
	private Channel channel;
	private NetworkInterface networkInterface;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;

	// XXX not used
	@Deprecated
	public InetAddress getIafinal() {
		return iafinal;
	}

	public NetworkInterface getNetworkInterface() {
		return networkInterface;
	}

	// use getNetworkInterface()
	@Deprecated
	public NetworkInterface getNi() {
		return getNetworkInterface();
	}

	public HTTPServer(int port) {
		this.port = port;
	}

	public String getURL() {
		return "http://" + hostname + ":" + port;
	}

	public String getHost() {
		return hostname;
	}

	public int getPort() {
		return port;
	}

	public boolean start() throws IOException {
		hostname = configuration.getServerHostname();
		InetSocketAddress address;

		if (StringUtils.isNotBlank(hostname)) {
			LOGGER.info("Using forced address " + hostname);
			InetAddress tempIA = InetAddress.getByName(hostname);

			if (tempIA != null && networkInterface != null && networkInterface.equals(NetworkInterface.getByInetAddress(tempIA))) {
				address = new InetSocketAddress(tempIA, port);
			} else {
				address = new InetSocketAddress(hostname, port);
			}
		} else if (isAddressFromInterfaceFound(configuration.getNetworkInterface())) { // XXX sets iafinal and networkInterface
			LOGGER.info("Using address {} found on network interface: {}", iafinal, networkInterface.toString().trim().replace('\n', ' '));
			address = new InetSocketAddress(iafinal, port);
		} else {
			LOGGER.info("Using localhost address");
			address = new InetSocketAddress(port);
		}

		LOGGER.info("Created socket: " + address);

		if (configuration.isHTTPEngineV2()) { // HTTP Engine V2
			bossGroup = new NioEventLoopGroup(1);
			workerGroup = new NioEventLoopGroup();

			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.childOption(ChannelOption.TCP_NODELAY, true)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.option(ChannelOption.SO_REUSEADDR, true)
					.childOption(ChannelOption.SO_REUSEADDR, true)
					.childOption(ChannelOption.SO_SNDBUF, 65536)
					.childOption(ChannelOption.SO_RCVBUF, 65536);
			bootstrap.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.localAddress(address)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) throws Exception {
							ch.pipeline()
									.addLast("HttpHandler", new HttpServerCodec())
									// eliminate the need to decode http chunks from the client
									.addLast("aggregator", new HttpObjectAggregator(64 * 1024))
									.addLast("chunkedWriter", new ChunkedWriteHandler())
									.addLast("handler", new RequestHandlerV2());
						}
					});

			try {
				channel = bootstrap.bind().channel();
			} catch (Exception e) {
				LOGGER.error("Another program is using port " + port + ", which UMS needs.");
				LOGGER.error("You can change the port UMS uses on the General Configuration tab.");
				LOGGER.trace("The error was: " + e);
				PMS.get().getFrame().setStatusCode(0, Messages.getString("PMS.141"), "icon-status-warning.png");
			}

			if (hostname == null && iafinal != null) {
				hostname = iafinal.getHostAddress();
			} else if (hostname == null) {
				hostname = InetAddress.getLocalHost().getHostAddress();
			}
		} else { // HTTP Engine V1
			serverSocketChannel = ServerSocketChannel.open();

			serverSocket = serverSocketChannel.socket();
			serverSocket.setReuseAddress(true);
			serverSocket.bind(address);

			if (hostname == null && iafinal != null) {
				hostname = iafinal.getHostAddress();
			} else if (hostname == null) {
				hostname = InetAddress.getLocalHost().getHostAddress();
			}

			runnable = new Thread(this, "HTTP Server");
			runnable.setDaemon(false);
			runnable.start();
		}

		return true;
	}

	// XXX this sets iafinal and networkInterface
	private boolean isAddressFromInterfaceFound(String networkInterfaceName) {
		NetworkConfiguration.InterfaceAssociation ia = StringUtils.isNotEmpty(networkInterfaceName) ?
			NetworkConfiguration.getInstance().getAddressForNetworkInterfaceName(networkInterfaceName) :
			null;

		if (ia == null) {
			ia = NetworkConfiguration.getInstance().getDefaultNetworkInterfaceAddress();
		}

		if (ia != null) {
			iafinal = ia.getAddr();
			networkInterface = ia.getIface();
		}

		return ia != null;
	}

	// http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&t=10689&p=48811#p48811
	//
	// avoid a NPE when a) switching HTTP Engine versions and b) restarting the HTTP server
	// by cleaning up based on what's in use (not null) rather than the config state, which
	// might be inconsistent.
	//
	// NOTE: there's little in the way of cleanup to do here as PMS.reset() discards the old
	// server and creates a new one
	public void stop() {
		LOGGER.info("Stopping server on host {} and port {}...", hostname, port);

		if (runnable != null) { // HTTP Engine V1
			runnable.interrupt();
		}

		if (serverSocket != null) { // HTTP Engine V1
			try {
				serverSocket.close();
				serverSocketChannel.close();
			} catch (IOException e) {
				LOGGER.debug("Caught exception", e);
			}
		}

		if (channel != null) { // HTTP Engine V2
			channel.close().syncUninterruptibly();
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}

		NetworkConfiguration.forgetConfiguration();
	}

	// XXX only used by HTTP Engine V1
	@Override
	public void run() {
		LOGGER.info("Starting DLNA Server on host {} and port {}...", hostname, port);

		while (!stop) {
			try {
				Socket socket = serverSocket.accept();
				InetAddress inetAddress = socket.getInetAddress();
				String ip = inetAddress.getHostAddress();
				// basic IP filter: solntcev at gmail dot com
				boolean ignore = false;

				if (configuration.getIpFiltering().allowed(inetAddress)) {
					LOGGER.trace("Receiving a request from: " + ip);
				} else {
					ignore = true;
					socket.close();
					LOGGER.trace("Ignoring request from: " + ip);
				}

				if (!ignore) {
					RequestHandler request = new RequestHandler(socket);
					Thread thread = new Thread(request, "Request Handler");
					thread.start();
				}
			} catch (ClosedByInterruptException e) {
				stop = true;
			} catch (IOException e) {
				LOGGER.debug("Caught exception", e);
			} finally {
				try {
					if (stop && serverSocket != null) {
						serverSocket.close();
					}

					if (stop && serverSocketChannel != null) {
						serverSocketChannel.close();
					}
				} catch (IOException e) {
					LOGGER.debug("Caught exception", e);
				}
			}
		}
	}
}
