package com.lky.kaipay.payment.service.acquirer;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit standalone registration only; never a nested Boot-test configuration. */
@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = SimulatedAuthorizationOperationRepository.class)
class SimulatorPersistenceTestConfiguration {
    enum CommitFault { NONE, COMMITTED_RESPONSE_LOST, ROLLED_BACK_RESPONSE_LOST }
    static class CommitFaults { final AtomicReference<CommitFault> next = new AtomicReference<>(CommitFault.NONE); }

    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan(SimulatedAuthorizationOperation.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        return factory;
    }
    @Bean CommitFaults commitFaults() { return new CommitFaults(); }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory, CommitFaults faults) {
        var delegate = new JpaTransactionManager(factory);
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return delegate.getTransaction(definition); }
            public void rollback(TransactionStatus status) { delegate.rollback(status); }
            public void commit(TransactionStatus status) {
                if (!status.isNewTransaction()) { delegate.commit(status); return; }
                CommitFault fault = faults.next.getAndSet(CommitFault.NONE);
                if (fault == CommitFault.ROLLED_BACK_RESPONSE_LOST) delegate.rollback(status);
                else delegate.commit(status);
                if (fault != CommitFault.NONE) throw new TransactionSystemException("Injected uncertain S commit acknowledgement");
            }
        };
    }
    @Bean SimulatedAuthorizationTransactionService simulatorTransactions(SimulatedAuthorizationOperationRepository repository) {
        return new SimulatedAuthorizationTransactionService(repository);
    }
    @Bean MockBankAcquirerClient simulatedClient(SimulatedAuthorizationTransactionService service, SimulatedAuthorizationOperationRepository repository) {
        return new MockBankAcquirerClient(service, repository);
    }
}
