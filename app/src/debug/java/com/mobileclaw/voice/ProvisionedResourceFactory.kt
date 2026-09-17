package com.mobileclaw.voice

/** Forces provisioning to supply the validated model directory before construction. */
internal class ProvisionedResourceFactory<T>(
    private val ensureModels: () -> String,
    private val construct: (String) -> T,
) {
    fun create(): T = construct(ensureModels())
}
