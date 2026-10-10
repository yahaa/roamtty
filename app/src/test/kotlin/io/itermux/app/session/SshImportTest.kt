package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SshImportTest {
    private fun one(text: String) = SshImport.parse(text).single()

    @Test fun readsSshCommandLines() {
        assertEquals(ImportedHost("dev.example.com", "dev.example.com", 2222, "me"), one("ssh -p 2222 me@dev.example.com"))
        assertEquals(ImportedHost("10.0.0.5", "10.0.0.5", 22, "root", "~/.ssh/id_ed25519"),
            one("  ssh -i ~/.ssh/id_ed25519 -l root 10.0.0.5 tmux attach  "))
        assertEquals(ImportedHost("box", "box", 2200, "ann"), one("ssh -o Port=2200 -o 'User ann' -A -t box"))
        assertEquals(ImportedHost("box", "box", 22, "a"), one("/usr/bin/ssh -p2222x a@box"))
        assertEquals(22, one("ssh -J jump.example.com me@inner").port)
        assertEquals("inner", one("ssh -J jump.example.com me@inner").address)
    }

    @Test fun readsUrlsAndBareTargets() {
        assertEquals(ImportedHost("dev", "dev", 2022, "me"), one("ssh://me@dev:2022/"))
        assertEquals(ImportedHost("dev", "dev", 2022, "me"), one("me@dev:2022"))
        assertEquals(ImportedHost("::1", "::1", 2022, "me"), one("me@[::1]:2022"))
        assertEquals(ImportedHost("fe80::1", "fe80::1", 22, ""), one("fe80::1"))
        assertEquals(ImportedHost("dev", "dev", 22, "me@corp"), one("me@corp@dev"))
    }

    @Test fun ignoresTextThatIsNoHost() {
        assertEquals(emptyList<ImportedHost>(), SshImport.parse(""))
        assertEquals(emptyList<ImportedHost>(), SshImport.parse("ssh -p 22"))
        assertEquals(emptyList<ImportedHost>(), SshImport.parse("hello world/path"))
        assertEquals(emptyList<ImportedHost>(), SshImport.parse("ssh://me@dev:abc"))
    }

    @Test fun readsConfigEntriesAndSkipsWildcards() {
        val hosts = SshImport.parse("""
            Host *
              ServerAliveInterval 30
              User everyone
            # work machines
            Host dev devbox
              HostName dev.example.com
              Port 2222
              User me
              IdentityFile "~/.ssh/work key"
            Host=pi
              HostName=192.168.1.7
            Match host x
              User nobody
            Host *.corp
              User c
        """.trimIndent())
        assertEquals(listOf(
            ImportedHost("dev", "dev.example.com", 2222, "me", "~/.ssh/work key"),
            ImportedHost("devbox", "dev.example.com", 2222, "me", "~/.ssh/work key"),
            ImportedHost("pi", "192.168.1.7", 22, ""),
        ), hosts)
    }

    @Test fun tokenizesLikeAShell() {
        assertEquals(listOf("ssh", "-o", "User a b", "it's", "x y"), SshImport.tokenize("""ssh -o "User a b" it\'s 'x y'"""))
    }
}
