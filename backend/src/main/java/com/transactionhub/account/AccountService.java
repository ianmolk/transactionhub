package com.transactionhub.account;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        Account account = repository.save(new Account(request.ownerName(), request.currency()));
        return AccountResponse.from(account);
    }

    public AccountResponse findById(UUID id) {
        return repository.findById(id).map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}
