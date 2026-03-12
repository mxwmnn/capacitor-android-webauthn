package io.github.mxwmnn.webauthn

/** ReplyChannel is the interface over which replies to the embedded site are sent. This allows
for testing because AndroidX bans mocking its objects.*/
interface ReplyChannel {
    fun send(message: String?)
}
