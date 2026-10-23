package com.botwithus.bot.core.secrets;

/** The {@link CredentialStore} contract against {@link InMemoryCredentialStore}. */
class InMemoryCredentialStoreTest extends CredentialStoreContract {

    @Override
    CredentialStore createStore() {
        return new InMemoryCredentialStore();
    }
}
