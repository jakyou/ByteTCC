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
package org.bytesoft.bytetcc;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionManagerImpl implements TransactionManager, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionManagerImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();

		if (invocation != null) {
			if (transaction == null) {
				this.beginInTryingPhaseForCoordinator(invocation);
			} else {
				TransactionContext transactionContext = transaction.getTransactionContext();
				if (transactionContext.isCompensating()) {
					this.beginInCompensatingPhaseForCoordinator(transaction);
				} else {
					this.beginInTryingPhaseForParticipant(transaction);
				}
			}
		} else if (transaction == null) {
			transactionManager.begin();
		} else {
			this.beginInCompensatingPhaseForParticipant(transaction);
		}

	}

	protected void beginInTryingPhaseForCoordinator(CompensableInvocation invocation) throws NotSupportedException,
			SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		compensableManager.begin();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
		TransactionContext transactionContext = transaction.getTransactionContext();
		transactionContext.setCompensable(true);

		transaction.registerCompensable(invocation);

		compensableLogger.createTransaction(transaction.getTransactionArchive());
	}

	protected void beginInCompensatingPhaseForCoordinator(CompensableTransaction tccTransaction)
			throws NotSupportedException, SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableArchive archive = tccTransaction.getCompensableArchive();

		TransactionContext tccTransactionContext = tccTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = (TransactionXid) archive.getCompensableXid();
		TransactionContext jtaTransactionContext = tccTransactionContext.clone();
		jtaTransactionContext.setXid(jtaTransactionXid);
		try {
			Transaction jtaTransaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			jtaTransaction.setTransactionalExtra(tccTransaction);
			tccTransaction.setTransactionalExtra(jtaTransaction);

			jtaTransaction.registerTransactionResourceListener(tccTransaction);
			jtaTransaction.registerTransactionListener(tccTransaction);
		} catch (TransactionException ex) {
			TransactionXid tccTransactionXid = tccTransactionContext.getXid();
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId()));
			throw new SystemException("Error occurred while beginning a jta-transaction!");
		}
	}

	protected void beginInCompensatingPhaseForParticipant(CompensableTransaction tccTransaction)
			throws NotSupportedException, SystemException {

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableArchive archive = tccTransaction.getCompensableArchive();

		TransactionContext tccTransactionContext = tccTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = (TransactionXid) archive.getCompensableXid();

		TransactionContext jtaTransactionContext = tccTransactionContext.clone();
		jtaTransactionContext.setXid(jtaTransactionXid);

		try {
			Transaction transaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(tccTransaction);
			tccTransaction.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(tccTransaction);
			transaction.registerTransactionListener(tccTransaction);
		} catch (TransactionException ex) {
			TransactionXid tccTransactionXid = tccTransactionContext.getXid();
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId()));
			throw new SystemException("Error occurred while beginning a jta-transaction!");
		}
	}

	protected void beginInTryingPhaseForParticipant(CompensableTransaction tccTransaction)
			throws NotSupportedException, SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
		TransactionContext tccTransactionContext = tccTransaction.getTransactionContext();
		TransactionXid tccTransactionXid = tccTransactionContext.getXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());
		TransactionContext jtaTransactionContext = tccTransactionContext.clone();
		jtaTransactionContext.setXid(jtaTransactionXid);
		try {
			Transaction jtaTransaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			jtaTransaction.setTransactionalExtra(tccTransaction);
			tccTransaction.setTransactionalExtra(jtaTransaction);

			jtaTransaction.registerTransactionResourceListener(tccTransaction);
			jtaTransaction.registerTransactionListener(tccTransaction);
		} catch (TransactionException ex) {
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId()));
			throw new SystemException("Error occurred while beginning a jta-transaction!");
		}

		// CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		// compensableLogger.createCompensable(compensable); // lazy
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		if (transactionContext.isCompensable() == false) {
			transactionManager.commit();
		} else if (transactionContext.isCompensating()) {
			compensableManager.commit();
		} else if (transactionContext.isCoordinator()) {
			compensableManager.compensableCommit();
		} else {
			compensableManager.commit();
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}

		if (transactionContext.isCompensable() == false) {
			transactionManager.rollback();
		} else if (transactionContext.isCoordinator()) {
			compensableManager.compensableRollback();
		} else {
			compensableManager.rollback();
		}

	}

	public Transaction suspend() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new SystemException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		return (isCompensableTransaction ? compensableManager : transactionManager).suspend();
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = ((Transaction) tobj).getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).resume(tobj);
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setTransactionTimeout(seconds);
	}

	public Transaction getTransaction() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction != null) {
			return jtaTransaction;
		} else if (tccTransaction != null) {
			return ((CompensableTransaction) tccTransaction).getTransaction();
		} else {
			return null;
		}
	}

	public Transaction getTransactionQuietly() {
		try {
			return this.getTransaction();
		} catch (Exception ex) {
			return null;
		}
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransaction();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getTransactionStatus();
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void associateThread(Transaction transaction) {
		throw new IllegalStateException();
	}

	public Transaction desociateThread() {
		throw new IllegalStateException();
	}

	public int getTimeoutSeconds() {
		throw new IllegalStateException();
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		throw new IllegalStateException();
	}
}
