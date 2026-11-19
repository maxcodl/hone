package android.content.pm;

/** Copy of the hidden framework callback, so the shell service can wait for each cache clear. */
oneway interface IPackageDataObserver {
    void onRemoveCompleted(String packageName, boolean succeeded);
}
