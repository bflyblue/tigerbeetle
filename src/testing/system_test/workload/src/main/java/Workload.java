import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;

public class Workload {
  static int ACCOUNTS_COUNT_MAX = 1024;
  static int BATCH_SIZE_MAX = 8190;

  Model model = new Model();
  Random random;
  Client client;

  public Workload(Random random, Client client) {
    this.random = random;
    this.client = client;
  }

  void run() {
    while (true) {
      var command = randomCommand();
      try {
        model.accept(command);
        System.out.printf("Executing: %s\n", command.pretty());
        execute(command);
      } catch (Throwable e) {
        System.out.printf("Failed while executing: %s\n", command.pretty());
        throw e;
      }
    }
  }

  Command randomCommand() {
    var commands = List.of(randomCreateAccounts(), randomCreateTransfers(), randomLookupAccounts())
        .stream().<Command>mapMulti(Optional::ifPresent).collect(Collectors.toList());

    assert !commands.isEmpty();

    return commands.get(random.nextInt(0, commands.size()));
  }

  Optional<Command> randomCreateAccounts() {
    int accountsCreatedCount = model.accountsCreatedCount();

    if (accountsCreatedCount < ACCOUNTS_COUNT_MAX) {
      var newAccounts = new NewAccount[random.nextInt(1,
          Math.min(ACCOUNTS_COUNT_MAX - accountsCreatedCount + 1, BATCH_SIZE_MAX))];

      for (int i = 0; i < newAccounts.length; i++) {
        var newAccount = new NewAccount();
        newAccount.id = random.nextLong();
        newAccount.ledger = 1;
        newAccount.code = random.nextInt(1, 100);
        newAccount.flags = random.nextBoolean() ? AccountFlags.HISTORY : AccountFlags.NONE;
        newAccounts[i] = newAccount;
      }
      CreateAccounts command = new CreateAccounts();
      command.accounts = newAccounts;

      return Optional.of(command);
    } else {
      return Optional.empty();
    }

  }

  Optional<Command> randomCreateTransfers() {
    // TODO: transfer between accounts in multiple ledgers
    int ledger = 1;
    AccountModel[] ledgerAccounts = model.ledgerAccounts(ledger);
    if (ledgerAccounts.length >= 2) {
      var newTransfers = new NewTransfer[random.nextInt(1, BATCH_SIZE_MAX)];

      for (int i = 0; i < newTransfers.length; i++) {
        var newTransfer = new NewTransfer();

        newTransfer.id = random.nextLong();
        newTransfer.ledger = ledger;
        newTransfer.code = random.nextInt(1, 100);
        newTransfer.amount = BigInteger.valueOf(random.nextLong());

        int debitAccountIndex = random.nextInt(0, ledgerAccounts.length);
        int creditAccountIndex = random.ints(0, ledgerAccounts.length)
            .filter((index) -> index != debitAccountIndex).findFirst().orElseThrow();
        newTransfer.debitAccountId = ledgerAccounts[debitAccountIndex].id;
        newTransfer.creditAccountId = ledgerAccounts[creditAccountIndex].id;

        newTransfers[i] = newTransfer;
      }

      CreateTransfers command = new CreateTransfers();
      command.transfers = newTransfers;
      return Optional.of(command);
    }


    return Optional.empty();
  }

  Optional<Command> randomLookupAccounts() {
    int ledger = 1;
    AccountModel[] ledgerAccounts = model.ledgerAccounts(ledger);
    if (ledgerAccounts.length >= 1) {
      int lookupBatchSize = random.nextInt(1, Math.min(ledgerAccounts.length, BATCH_SIZE_MAX) + 1);
      int startIndex = ledgerAccounts.length > lookupBatchSize
          ? random.nextInt(0, ledgerAccounts.length - lookupBatchSize)
          : 0;

      var ids = new long[lookupBatchSize];
      for (int i = 0; i < lookupBatchSize; i++) {
        ids[i] = ledgerAccounts[startIndex + i].id;
      }

      LookupAccounts command = new LookupAccounts();
      command.ids = ids;
      return Optional.of(command);
    }

    return Optional.empty();
  }

  void execute(Command command) {
    if (command instanceof CreateAccounts) {
      CreateAccounts createAccounts = (CreateAccounts) command;
      AccountBatch accounts = new AccountBatch(createAccounts.accounts.length);
      // System.out.printf("Creating %d accounts\n", createAccounts.accounts.length);
      for (NewAccount account : createAccounts.accounts) {
        accounts.add();
        accounts.setId(account.id);
        accounts.setLedger(account.ledger);
        accounts.setCode(account.code);
        accounts.setFlags(account.flags);
      }

      CreateAccountResultBatch accountErrors = client.createAccounts(accounts);
      while (accountErrors.next()) {
        switch (accountErrors.getResult()) {
          default:
            System.err.printf("Error creating account %d: %s\n", accountErrors.getIndex(),
                accountErrors.getResult());
            assert false;
        }
      }
    } else if (command instanceof CreateTransfers) {
      CreateTransfers createTransfers = (CreateTransfers) command;
      TransferBatch transfers = new TransferBatch(createTransfers.transfers.length);

      for (NewTransfer transfer : createTransfers.transfers) {
        transfers.add();

        transfers.setId(transfer.id);
        transfers.setDebitAccountId(transfer.debitAccountId);
        transfers.setCreditAccountId(transfer.creditAccountId);
        transfers.setLedger(transfer.ledger);
        transfers.setCode(transfer.code);
        transfers.setAmount(transfer.amount);
      }

      CreateTransferResultBatch transferErrors = client.createTransfers(transfers);
      while (transferErrors.next()) {
        switch (transferErrors.getResult()) {
          default:
            System.err.printf("Error creating transfer %d: %s\n", transferErrors.getIndex(),
                transferErrors.getResult());
            assert false;
        }
      }

    } else if (command instanceof LookupAccounts) {
      LookupAccounts lookupAccounts = (LookupAccounts) command;
      IdBatch ids = new IdBatch(lookupAccounts.ids.length);
      for (long id : lookupAccounts.ids) {
        ids.add(id);
      }
      AccountBatch accounts = client.lookupAccounts(ids);
      assert accounts.getLength() == lookupAccounts.ids.length;
      // TODO: parse batch and return query results (as model?)
    } else {
      throw new IllegalArgumentException("Invalid command: %s".formatted(command));
    }
  }
}
