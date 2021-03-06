/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.dubbo.spi;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.dubbo.InvocationContext;
import org.bytesoft.bytejta.supports.dubbo.InvocationContextRegistry;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.springframework.core.env.Environment;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

public final class CompensableLoadBalance implements LoadBalance {
	static final String CONSTANT_LOADBALANCE_KEY = "org.bytesoft.bytetcc.loadbalance";

	private ILoadBalancer loadBalancer;

	private void fireInitializeIfNecessary() {
		if (this.loadBalancer == null) {
			this.initializeIfNecessary();
		}
	}

	private synchronized void initializeIfNecessary() {
		if (this.loadBalancer == null) {
			Environment environment = CompensableBeanRegistry.getInstance().getEnvironment();
			String loadBalanceKey = environment.getProperty(CONSTANT_LOADBALANCE_KEY, "default");
			ExtensionLoader<ILoadBalancer> extensionLoader = ExtensionLoader.getExtensionLoader(ILoadBalancer.class);
			this.loadBalancer = extensionLoader.getExtension(loadBalanceKey);
		}
	}

	public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		InvocationContextRegistry registry = InvocationContextRegistry.getInstance();
		InvocationContext invocationContext = registry.getInvocationContext();
		if (invocationContext == null) {
			return this.selectInvokerForSVC(invokers, url, invocation);
		} else {
			return this.selectInvokerForTCC(invokers, url, invocation);
		}
	}

	public <T> Invoker<T> selectInvokerForSVC(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		if (invokers == null || invokers.isEmpty()) {
			throw new RpcException("No invoker is found!");
		}

		CompensableBeanFactory beanFactory = CompensableBeanRegistry.getInstance().getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
		List<XAResourceArchive> participantList = compensable == null ? null : compensable.getParticipantArchiveList();

		for (int i = 0; invokers != null && participantList != null && participantList.isEmpty() == false
				&& i < invokers.size(); i++) {
			Invoker<T> invoker = invokers.get(i);
			URL invokerUrl = invoker.getUrl();
			String invokerHost = invokerUrl.getHost();
			int invokerPort = invokerUrl.getPort();
			String invokerAddr = String.format("%s:%s", invokerHost, invokerPort);
			for (int j = 0; participantList != null && j < participantList.size(); j++) {
				XAResourceArchive archive = participantList.get(j);
				XAResourceDescriptor descriptor = archive.getDescriptor();
				String identifier = descriptor.getIdentifier();
				String[] values = identifier == null ? new String[0] : identifier.split("\\s*:\\s*");
				String targetAddr = values.length == 3 ? values[0] : null;
				String targetPort = values.length == 3 ? values[2] : null;
				String remoteAddr = String.format("%s:%s", targetAddr, targetPort);
				if (StringUtils.equalsIgnoreCase(invokerAddr, remoteAddr)) {
					return invoker;
				} // end-if (StringUtils.equalsIgnoreCase(invokerAddr, remoteAddr))
			}
		}

		this.fireInitializeIfNecessary();

		if (this.loadBalancer == null) {
			throw new RpcException("No org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer is found!");
		} else {
			return this.loadBalancer.select(invokers, url, invocation);
		}

	}

	public <T> Invoker<T> selectInvokerForTCC(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		InvocationContextRegistry registry = InvocationContextRegistry.getInstance();
		InvocationContext invocationContext = registry.getInvocationContext();

		String serverHost = invocationContext.getServerHost();
		int serverPort = invocationContext.getServerPort();
		for (int i = 0; invokers != null && i < invokers.size(); i++) {
			Invoker<T> invoker = invokers.get(i);
			URL targetUrl = invoker.getUrl();
			String targetAddr = targetUrl.getIp();
			int targetPort = targetUrl.getPort();
			if (StringUtils.equals(targetAddr, serverHost) && targetPort == serverPort) {
				return invoker;
			}
		}

		throw new RpcException(String.format("Invoker(%s:%s) is not found!", serverHost, serverPort));
	}
}
