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
package org.bytesoft.bytetcc.supports.spring;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableMethodInterceptor
		implements MethodInterceptor, ApplicationContextAware, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;
	private ApplicationContext applicationContext;

	public Object invoke(MethodInvocation mi) throws Throwable {
		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		Compensable compensable = mi.getMethod().getDeclaringClass().getAnnotation(Compensable.class);

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();

		try {
			CompensableInvocationImpl invocation = new CompensableInvocationImpl();
			Method method = mi.getMethod();
			invocation.setMethod(compensable.interfaceClass().getMethod(method.getName(), method.getParameterTypes()));
			invocation.setArgs(mi.getArguments());
			invocation.setCancellableKey(compensable.cancellableKey());
			invocation.setConfirmableKey(compensable.confirmableKey());
			String[] beanNameArray = this.applicationContext.getBeanNamesForType(mi.getThis().getClass());
			if (beanNameArray.length == 1) {
				invocation.setIdentifier(beanNameArray[0]);
			} else {
				throw new IllegalStateException(); // TODO
			}

			if (transaction != null) {
				Transactional transactional = mi.getMethod().getAnnotation(Transactional.class);
				Propagation propagation = transactional == null ? null : transactional.propagation();
				if (transactional == null) {
					// should never happen
				} else if (propagation == null) {
					transaction.registerCompensable(invocation);
				} else if (Propagation.REQUIRED.equals(propagation)) {
					transaction.registerCompensable(invocation);
				} else if (Propagation.MANDATORY.equals(propagation)) {
					transaction.registerCompensable(invocation);
				} else if (Propagation.SUPPORTS.equals(propagation)) {
					transaction.registerCompensable(invocation);
				}
			}

			registry.register(invocation);
			return mi.proceed();
		} finally {
			registry.unegister();
		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

}
