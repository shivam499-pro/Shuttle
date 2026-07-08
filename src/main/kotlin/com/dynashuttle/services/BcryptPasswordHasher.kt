package com.dynashuttle.services

import at.favre.lib.crypto.bcrypt.BCrypt

class BcryptPasswordHasher : PasswordHasher {
    override fun hash(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }

    override fun verify(password: String, hashedPassword: String): Boolean {
        val result = BCrypt.verifyer().verify(password.toCharArray(), hashedPassword.toCharArray())
        return result.verified
    }
}
