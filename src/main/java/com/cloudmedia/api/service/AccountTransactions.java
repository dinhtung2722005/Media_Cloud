package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.repository.UserRepository;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountTransactions {
  private final UserRepository users;
  private final TransactionTemplate transactions;

  public AccountTransactions(UserRepository users, PlatformTransactionManager manager) {
    this.users = users;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setTimeout(30);
  }

  public <T> T locked(String userId, Supplier<T> action) {
    return transactions.execute(
        status -> {
          users.lockById(userId).orElseThrow(DomainException::missing);
          return action.get();
        });
  }

  public void locked(String userId, Runnable action) {
    locked(
        userId,
        () -> {
          action.run();
          return null;
        });
  }

  public static void afterCommit(Runnable action) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  action.run();
                }
              });
    else action.run();
  }
}
