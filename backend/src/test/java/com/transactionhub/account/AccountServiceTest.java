package com.transactionhub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock
    private AccountRepository repository;
    @InjectMocks
    private AccountService service;

    @Test
    void createsAccountWithNormalizedOwnerAndZeroBalance() {
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse result = service.create(new CreateAccountRequest("  Alex Smith  ", Currency.AUD));

        assertThat(result.id()).isNotNull();
        assertThat(result.ownerName()).isEqualTo("Alex Smith");
        assertThat(result.currency()).isEqualTo(Currency.AUD);
        assertThat(result.balance()).isEqualByComparingTo("0.00");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.createdAt().getNano() % 1_000).isZero();
        verify(repository).save(any(Account.class));
    }

    @Test
    void retrievesExistingAccount() {
        Account account = new Account("Alex Smith", Currency.USD);
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThat(service.findById(account.getId())).isEqualTo(AccountResponse.from(account));
    }

    @Test
    void rejectsMissingAccount() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
