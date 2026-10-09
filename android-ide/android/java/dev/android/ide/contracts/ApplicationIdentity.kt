// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

object ApplicationIdentity {
    const val TARGET_APPLICATION_ID = "com.termux"
    const val TARGET_METADATA_DIRECTORY = ".dev-android-ide"
    const val LEGACY_APPLICATION_ID = "dev.android.ide"
    const val LEGACY_METADATA_DIRECTORY = ".androidide"
}
