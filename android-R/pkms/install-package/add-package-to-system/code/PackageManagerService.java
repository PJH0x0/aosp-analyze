    void installStage(ActiveInstallSession activeInstallSession) {
        if (DEBUG_INSTANT) {
            if ((activeInstallSession.getSessionParams().installFlags
                        & PackageManager.INSTALL_INSTANT_APP) != 0) {
                Slog.d(TAG, "Ephemeral install of " + activeInstallSession.getPackageName());
                        }
        }
        final Message msg = mHandler.obtainMessage(INIT_COPY);
        final InstallParams params = new InstallParams(activeInstallSession);
        params.setTraceMethod("installStage").setTraceCookie(System.identityHashCode(params));
        msg.obj = params;
    
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "installStage",
                System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(msg.obj));
    
        mHandler.sendMessage(msg);
    }
    
    private abstract class HandlerParams {
        /** User handle for the user requesting the information or installation. */
        private final UserHandle mUser;
        String traceMethod;
        int traceCookie;
    
        HandlerParams(UserHandle user) {
            mUser = user;
        }
    
        UserHandle getUser() {
            return mUser;
        }
    
        /**
         * Gets the user handle for the user that the rollback agent should
         * use to look up information about this installation when enabling
         * rollback.
         */
        UserHandle getRollbackUser() {
            // The session for packages installed for "all" users is
            // associated with the "system" user.
            if (mUser == UserHandle.ALL) {
                return UserHandle.SYSTEM;
            }
            return mUser;
        }
    
        HandlerParams setTraceMethod(String traceMethod) {
            this.traceMethod = traceMethod;
            return this;
        }
    
        HandlerParams setTraceCookie(int traceCookie) {
            this.traceCookie = traceCookie;
            return this;
        }
    
        final void startCopy() {
            if (DEBUG_INSTALL) Slog.i(TAG, "startCopy " + mUser + ": " + this);
            handleStartCopy();
            handleReturnCode();
        }
    
        abstract void handleStartCopy();
        abstract void handleReturnCode();
    }

    class InstallParams extends HandlerParams {
        // TODO: see if we can collapse this into ActiveInstallSession
    
        final OriginInfo origin;
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        int installFlags;
        @NonNull final InstallSource installSource;
        final String volumeUuid;
        private boolean mVerificationCompleted;
        private boolean mIntegrityVerificationCompleted;
        private boolean mEnableRollbackCompleted;
        private InstallArgs mArgs;
        int mRet;
        final String packageAbiOverride;
        final String[] grantedRuntimePermissions;
        final List<String> whitelistedRestrictedPermissions;
        final int autoRevokePermissionsMode;
        final VerificationInfo verificationInfo;
        final PackageParser.SigningDetails signingDetails;
        final int installReason;
        @Nullable
        MultiPackageInstallParams mParentInstallParams;
        final long requiredInstalledVersionCode;
        final boolean forceQueryableOverride;
        final int mDataLoaderType;
        final int mSessionId;
    
        InstallParams(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer,
                int installFlags, InstallSource installSource, String volumeUuid,
                VerificationInfo verificationInfo, UserHandle user, String packageAbiOverride,
                String[] grantedPermissions, List<String> whitelistedRestrictedPermissions,
                int autoRevokePermissionsMode,
                SigningDetails signingDetails, int installReason,
                long requiredInstalledVersionCode, int dataLoaderType) {
            super(user);
            this.origin = origin;
            this.move = move;
            this.observer = observer;
            this.installFlags = installFlags;
            this.installSource = Preconditions.checkNotNull(installSource);
            this.volumeUuid = volumeUuid;
            this.verificationInfo = verificationInfo;
            this.packageAbiOverride = packageAbiOverride;
            this.grantedRuntimePermissions = grantedPermissions;
            this.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
            this.autoRevokePermissionsMode = autoRevokePermissionsMode;
            this.signingDetails = signingDetails;
            this.installReason = installReason;
            this.requiredInstalledVersionCode = requiredInstalledVersionCode;
            this.forceQueryableOverride = false;
            this.mDataLoaderType = dataLoaderType;
            this.mSessionId = -1;
        }
    
        InstallParams(ActiveInstallSession activeInstallSession) {
            super(activeInstallSession.getUser());
            final PackageInstaller.SessionParams sessionParams =
                activeInstallSession.getSessionParams();
            if (DEBUG_INSTANT) {
                if ((sessionParams.installFlags
                            & PackageManager.INSTALL_INSTANT_APP) != 0) {
                    Slog.d(TAG, "Ephemeral install of " + activeInstallSession.getPackageName());
                            }
            }
            verificationInfo = new VerificationInfo(
                    sessionParams.originatingUri,
                    sessionParams.referrerUri,
                    sessionParams.originatingUid,
                    activeInstallSession.getInstallerUid());
            origin = OriginInfo.fromStagedFile(activeInstallSession.getStagedDir());
            move = null;
            installReason = fixUpInstallReason(
                    activeInstallSession.getInstallSource().installerPackageName,
                    activeInstallSession.getInstallerUid(),
                    sessionParams.installReason);
            observer = activeInstallSession.getObserver();
            installFlags = sessionParams.installFlags;
            installSource = activeInstallSession.getInstallSource();
            volumeUuid = sessionParams.volumeUuid;
            packageAbiOverride = sessionParams.abiOverride;
            grantedRuntimePermissions = sessionParams.grantedRuntimePermissions;
            whitelistedRestrictedPermissions = sessionParams.whitelistedRestrictedPermissions;
            autoRevokePermissionsMode = sessionParams.autoRevokePermissionsMode;
            signingDetails = activeInstallSession.getSigningDetails();
            requiredInstalledVersionCode = sessionParams.requiredInstalledVersionCode;
            forceQueryableOverride = sessionParams.forceQueryableOverride;
            mDataLoaderType = (sessionParams.dataLoaderParams != null)
                ? sessionParams.dataLoaderParams.getType() : DataLoaderType.NONE;
            mSessionId = activeInstallSession.getSessionId();
        }
    
        @Override
        public String toString() {
            return "InstallParams{" + Integer.toHexString(System.identityHashCode(this))
                + " file=" + origin.file + "}";
        }
    
        private int installLocationPolicy(PackageInfoLite pkgLite) {
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            // reader
            synchronized (mLock) {
                // Currently installed package which the new package is attempting to replace or
                // null if no such package is installed.
                AndroidPackage installedPkg = mPackages.get(packageName);
                // Package which currently owns the data which the new package will own if installed.
                // If an app is unstalled while keeping data (e.g., adb uninstall -k), installedPkg
                // will be null whereas dataOwnerPkg will contain information about the package
                // which was uninstalled while keeping its data.
                AndroidPackage dataOwnerPkg = installedPkg;
                if (dataOwnerPkg  == null) {
                    PackageSetting ps = mSettings.mPackages.get(packageName);
                    if (ps != null) {
                        dataOwnerPkg = ps.pkg;
                    }
                }
    
                if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST) {
                    if (dataOwnerPkg == null) {
                        Slog.w(TAG, "Required installed version code was "
                                + requiredInstalledVersionCode
                                + " but package is not installed");
                        return PackageHelper.RECOMMEND_FAILED_WRONG_INSTALLED_VERSION;
                    }
    
                    if (dataOwnerPkg.getLongVersionCode() != requiredInstalledVersionCode) {
                        Slog.w(TAG, "Required installed version code was "
                                + requiredInstalledVersionCode
                                + " but actual installed version is "
                                + dataOwnerPkg.getLongVersionCode());
                        return PackageHelper.RECOMMEND_FAILED_WRONG_INSTALLED_VERSION;
                    }
                }
    
                if (dataOwnerPkg != null) {
                    if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags,
                                dataOwnerPkg.isDebuggable())) {
                        try {
                            checkDowngrade(dataOwnerPkg, pkgLite);
                        } catch (PackageManagerException e) {
                            Slog.w(TAG, "Downgrade detected: " + e.getMessage());
                            return PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE;
                        }
                    }
                }
    
                if (installedPkg != null) {
                    if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                        // Check for updated system application.
                        if (installedPkg.isSystem()) {
                            return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                        } else {
                            // If current upgrade specifies particular preference
                            if (installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                                // Application explicitly specified internal.
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            } else if (installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                                // App explictly prefers external. Let policy decide
                            } else {
                                // Prefer previous location
                                if (installedPkg.isExternalStorage()) {
                                    return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                                }
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            }
                        }
                    } else {
                        // Invalid install. Return error code
                        return PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS;
                    }
                }
            }
            return pkgLite.recommendedInstallLocation;
        }
    
        /*
         * Invoke remote method to get package information and install
         * location values. Override install location based on default
         * policy if needed and then create install arguments based
         * on the install location.
         */
        public void handleStartCopy() {
            int ret = PackageManager.INSTALL_SUCCEEDED;
    
            // If we're already staged, we've firmly committed to an install location
            if (origin.staged) {
                if (origin.file != null) {
                    installFlags |= PackageManager.INSTALL_INTERNAL;
                } else {
                    throw new IllegalStateException("Invalid stage location");
                }
            }
    
            final boolean onInt = (installFlags & PackageManager.INSTALL_INTERNAL) != 0;
            final boolean ephemeral = (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
            PackageInfoLite pkgLite = null;
    
    
            pkgLite = PackageManagerServiceUtils.getMinimalPackageInfo(mContext,
                    origin.resolvedPath, installFlags, packageAbiOverride);
    
            if (DEBUG_INSTANT && ephemeral) {
                Slog.v(TAG, "pkgLite for install: " + pkgLite);
            }
    
            /*
             * If we have too little free space, try to free cache
             * before giving up.
             */
            if (!origin.staged && pkgLite.recommendedInstallLocation
                    == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                // TODO: focus freeing disk space on the target device
                final StorageManager storage = StorageManager.from(mContext);
                final long lowThreshold = storage.getStorageLowBytes(
                        Environment.getDataDirectory());
    
                final long sizeBytes = PackageManagerServiceUtils.calculateInstalledSize(
                        origin.resolvedPath, packageAbiOverride);
                if (sizeBytes >= 0) {
                    try {
                        mInstaller.freeCache(null, sizeBytes + lowThreshold, 0, 0);
                        pkgLite = PackageManagerServiceUtils.getMinimalPackageInfo(mContext,
                                origin.resolvedPath, installFlags, packageAbiOverride);
                    } catch (InstallerException e) {
                        Slog.w(TAG, "Failed to free cache", e);
                    }
                }
    
                /*
                 * The cache free must have deleted the file we downloaded to install.
                 *
                 * TODO: fix the "freeCache" call to not delete the file we care about.
                 */
                if (pkgLite.recommendedInstallLocation
                        == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                    pkgLite.recommendedInstallLocation
                        = PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
                        }
                    }
    
    
            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                int loc = pkgLite.recommendedInstallLocation;
                if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS) {
                    ret = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                    ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_APK) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_APK;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_URI;
                } else if (loc == PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE) {
                    ret = PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
                } else {
                    // Override with defaults if needed.
                    loc = installLocationPolicy(pkgLite);
                    if (loc == PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE) {
                        ret = PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
                    } else if (loc == PackageHelper.RECOMMEND_FAILED_WRONG_INSTALLED_VERSION) {
                        ret = PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION;
                    } else if (!onInt) {
                        // Override install location with flags
                        if (loc == PackageHelper.RECOMMEND_INSTALL_EXTERNAL) {
                            // Set the flag to install on external media.
                            installFlags &= ~PackageManager.INSTALL_INTERNAL;
                        } else if (loc == PackageHelper.RECOMMEND_INSTALL_EPHEMERAL) {
                            if (DEBUG_INSTANT) {
                                Slog.v(TAG, "...setting INSTALL_EPHEMERAL install flag");
                            }
                            installFlags |= PackageManager.INSTALL_INSTANT_APP;
                            installFlags &= ~PackageManager.INSTALL_INTERNAL;
                        } else {
                            // Make sure the flag for installing on external
                            // media is unset
                            installFlags |= PackageManager.INSTALL_INTERNAL;
                        }
                    }
                }
            }
    
            final InstallArgs args = createInstallArgs(this);
            mVerificationCompleted = true;
            mIntegrityVerificationCompleted = true;
            mEnableRollbackCompleted = true;
            mArgs = args;
    
            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                final int verificationId = mPendingVerificationToken++;
    
                // Perform package verification (unless we are simply moving the package).
                if (!origin.existing) {
                    PackageVerificationState verificationState =
                        new PackageVerificationState(this);
                    mPendingVerification.append(verificationId, verificationState);
    
                    sendIntegrityVerificationRequest(verificationId, pkgLite, verificationState);
                    ret = sendPackageVerificationRequest(
                            verificationId, pkgLite, verificationState);
    
                    // If both verifications are skipped, we should remove the state.
                    if (verificationState.areAllVerificationsComplete()) {
                        mPendingVerification.remove(verificationId);
                    }
                }
    
    
                if ((installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0) {
                    // TODO(ruhler) b/112431924: Don't do this in case of 'move'?
                    final int enableRollbackToken = mPendingEnableRollbackToken++;
                    Trace.asyncTraceBegin(
                            TRACE_TAG_PACKAGE_MANAGER, "enable_rollback", enableRollbackToken);
                    mPendingEnableRollback.append(enableRollbackToken, this);
    
                    Intent enableRollbackIntent = new Intent(Intent.ACTION_PACKAGE_ENABLE_ROLLBACK);
                    enableRollbackIntent.putExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN,
                            enableRollbackToken);
                    enableRollbackIntent.putExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID,
                            mSessionId);
                    enableRollbackIntent.setType(PACKAGE_MIME_TYPE);
                    enableRollbackIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    
                    // Allow the broadcast to be sent before boot complete.
                    // This is needed when committing the apk part of a staged
                    // session in early boot. The rollback manager registers
                    // its receiver early enough during the boot process that
                    // it will not miss the broadcast.
                    enableRollbackIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
    
                    mContext.sendOrderedBroadcastAsUser(enableRollbackIntent, UserHandle.SYSTEM,
                            android.Manifest.permission.PACKAGE_ROLLBACK_AGENT,
                            new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    // the duration to wait for rollback to be enabled, in millis
                                    long rollbackTimeout = DeviceConfig.getLong(
                                            DeviceConfig.NAMESPACE_ROLLBACK,
                                            PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                                            DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS);
                                    if (rollbackTimeout < 0) {
                                        rollbackTimeout = DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS;
                                    }
                                    final Message msg = mHandler.obtainMessage(
                                            ENABLE_ROLLBACK_TIMEOUT);
                                    msg.arg1 = enableRollbackToken;
                                    msg.arg2 = mSessionId;
                                    mHandler.sendMessageDelayed(msg, rollbackTimeout);
                                }
                            }, null, 0, null, null);
    
                    mEnableRollbackCompleted = false;
                }
            }
    
            mRet = ret;
        }
    
        /**
         * Send a request to check the integrity of the package.
         */
        void sendIntegrityVerificationRequest(
                int verificationId,
                PackageInfoLite pkgLite,
                PackageVerificationState verificationState) {
            if (!isIntegrityVerificationEnabled()) {
                // Consider the integrity check as passed.
                verificationState.setIntegrityVerificationResult(
                        PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
                return;
            }
    
            final Intent integrityVerification =
                new Intent(Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
    
            integrityVerification.setDataAndType(Uri.fromFile(new File(origin.resolvedPath)),
                    PACKAGE_MIME_TYPE);
    
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND;
            integrityVerification.addFlags(flags);
    
            integrityVerification.putExtra(EXTRA_VERIFICATION_ID, verificationId);
            integrityVerification.putExtra(EXTRA_PACKAGE_NAME, pkgLite.packageName);
            integrityVerification.putExtra(EXTRA_VERSION_CODE, pkgLite.versionCode);
            integrityVerification.putExtra(EXTRA_LONG_VERSION_CODE, pkgLite.getLongVersionCode());
            populateInstallerExtras(integrityVerification);
    
            // send to integrity component only.
            integrityVerification.setPackage("android");
    
            DeviceIdleInternal idleController =
                mInjector.getLocalDeviceIdleController();
            final long idleDuration = getVerificationTimeout();
    
            idleController.addPowerSaveTempWhitelistAppDirect(Process.myUid(),
                    idleDuration,
                    false, "integrity component");
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setTemporaryAppWhitelistDuration(idleDuration);
    
            mContext.sendOrderedBroadcastAsUser(integrityVerification, UserHandle.SYSTEM,
                    /* receiverPermission= */ null,
                    /* appOp= */ AppOpsManager.OP_NONE,
                    /* options= */ options.toBundle(),
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            final Message msg =
                                mHandler.obtainMessage(CHECK_PENDING_INTEGRITY_VERIFICATION);
                            msg.arg1 = verificationId;
                            mHandler.sendMessageDelayed(msg, getIntegrityVerificationTimeout());
                        }
                    }, /* scheduler= */ null,
                    /* initialCode= */ 0,
                    /* initialData= */ null,
                    /* initialExtras= */ null);
    
            Trace.asyncTraceBegin(
                    TRACE_TAG_PACKAGE_MANAGER, "integrity_verification", verificationId);
    
            // stop the copy until verification succeeds.
            mIntegrityVerificationCompleted = false;
                }
    
        /**
         * Send a request to verifier(s) to verify the package if necessary, and return
         * {@link PackageManager#INSTALL_SUCCEEDED} if succeeded.
         */
        int sendPackageVerificationRequest(
                int verificationId,
                PackageInfoLite pkgLite,
                PackageVerificationState verificationState) {
            int ret = INSTALL_SUCCEEDED;
    
            // TODO: http://b/22976637
            // Apps installed for "all" users use the device owner to verify the app
            UserHandle verifierUser = getUser();
            if (verifierUser == UserHandle.ALL) {
                verifierUser = UserHandle.SYSTEM;
            }
    
            /*
             * Determine if we have any installed package verifiers. If we
             * do, then we'll defer to them to verify the packages.
             */
            final int requiredUid = mRequiredVerifierPackage == null ? -1
                : getPackageUid(mRequiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        verifierUser.getIdentifier());
            verificationState.setRequiredVerifierUid(requiredUid);
            final int installerUid =
                verificationInfo == null ? -1 : verificationInfo.installerUid;
            final boolean isVerificationEnabled = isVerificationEnabled(
                    pkgLite, verifierUser.getIdentifier(), installFlags, installerUid);
            final boolean isV4Signed =
                (mArgs.signingDetails.signatureSchemeVersion == SIGNING_BLOCK_V4);
            final boolean isIncrementalInstall =
                (mArgs.mDataLoaderType == DataLoaderType.INCREMENTAL);
            // NOTE: We purposefully skip verification for only incremental installs when there's
            // a v4 signature block. Otherwise, proceed with verification as usual.
            if (!origin.existing
                    && isVerificationEnabled
                    && (!isIncrementalInstall || !isV4Signed)) {
                final Intent verification = new Intent(
                        Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
                verification.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                verification.setDataAndType(Uri.fromFile(new File(origin.resolvedPath)),
                        PACKAGE_MIME_TYPE);
                verification.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    
                // Query all live verifiers based on current user state
                final List<ResolveInfo> receivers = queryIntentReceiversInternal(verification,
                        PACKAGE_MIME_TYPE, 0, verifierUser.getIdentifier(),
                        false /*allowDynamicSplits*/);
    
                if (DEBUG_VERIFY) {
                    Slog.d(TAG, "Found " + receivers.size() + " verifiers for intent "
                            + verification.toString() + " with " + pkgLite.verifiers.length
                            + " optional verifiers");
                }
    
                verification.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);
    
                verification.putExtra(
                        PackageManager.EXTRA_VERIFICATION_INSTALL_FLAGS, installFlags);
    
                verification.putExtra(
                        PackageManager.EXTRA_VERIFICATION_PACKAGE_NAME, pkgLite.packageName);
    
                verification.putExtra(
                        PackageManager.EXTRA_VERIFICATION_VERSION_CODE, pkgLite.versionCode);
    
                verification.putExtra(
                        PackageManager.EXTRA_VERIFICATION_LONG_VERSION_CODE,
                        pkgLite.getLongVersionCode());
    
                populateInstallerExtras(verification);
    
                final List<ComponentName> sufficientVerifiers = matchVerifiers(pkgLite,
                        receivers, verificationState);
    
                DeviceIdleInternal idleController =
                    mInjector.getLocalDeviceIdleController();
                final long idleDuration = getVerificationTimeout();
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setTemporaryAppWhitelistDuration(idleDuration);
    
                /*
                 * If any sufficient verifiers were listed in the package
                 * manifest, attempt to ask them.
                 */
                if (sufficientVerifiers != null) {
                    final int n = sufficientVerifiers.size();
                    if (n == 0) {
                        Slog.i(TAG, "Additional verifiers required, but none installed.");
                        ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
                    } else {
                        for (int i = 0; i < n; i++) {
                            final ComponentName verifierComponent = sufficientVerifiers.get(i);
                            idleController.addPowerSaveTempWhitelistApp(Process.myUid(),
                                    verifierComponent.getPackageName(), idleDuration,
                                    verifierUser.getIdentifier(), false, "package verifier");
    
                            final Intent sufficientIntent = new Intent(verification);
                            sufficientIntent.setComponent(verifierComponent);
                            mContext.sendBroadcastAsUser(sufficientIntent, verifierUser,
                                    /* receiverPermission= */ null,
                                    options.toBundle());
                        }
                    }
                }
    
                if (mRequiredVerifierPackage != null) {
                    final ComponentName requiredVerifierComponent = matchComponentForVerifier(
                            mRequiredVerifierPackage, receivers);
                    /*
                     * Send the intent to the required verification agent,
                     * but only start the verification timeout after the
                     * target BroadcastReceivers have run.
                     */
                    verification.setComponent(requiredVerifierComponent);
                    idleController.addPowerSaveTempWhitelistApp(Process.myUid(),
                            mRequiredVerifierPackage, idleDuration,
                            verifierUser.getIdentifier(), false, "package verifier");
                    mContext.sendOrderedBroadcastAsUser(verification, verifierUser,
                            android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                            /* appOp= */ AppOpsManager.OP_NONE,
                            /* options= */ options.toBundle(),
                            new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    final Message msg = mHandler
                                        .obtainMessage(CHECK_PENDING_VERIFICATION);
                                    msg.arg1 = verificationId;
                                    mHandler.sendMessageDelayed(msg, getVerificationTimeout());
                                }
                            }, null, 0, null, null);
    
                    Trace.asyncTraceBegin(
                            TRACE_TAG_PACKAGE_MANAGER, "verification", verificationId);
    
                    /*
                     * We don't want the copy to proceed until verification
                     * succeeds.
                     */
                    mVerificationCompleted = false;
                }
            } else {
                verificationState.setVerifierResponse(
                        requiredUid, PackageManager.VERIFICATION_ALLOW);
            }
            return ret;
                }
    
        void populateInstallerExtras(Intent intent) {
            intent.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE,
                    installSource.initiatingPackageName);
    
            if (verificationInfo != null) {
                if (verificationInfo.originatingUri != null) {
                    intent.putExtra(Intent.EXTRA_ORIGINATING_URI,
                            verificationInfo.originatingUri);
                }
                if (verificationInfo.referrer != null) {
                    intent.putExtra(Intent.EXTRA_REFERRER,
                            verificationInfo.referrer);
                }
                if (verificationInfo.originatingUid >= 0) {
                    intent.putExtra(Intent.EXTRA_ORIGINATING_UID,
                            verificationInfo.originatingUid);
                }
                if (verificationInfo.installerUid >= 0) {
                    intent.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_UID,
                            verificationInfo.installerUid);
                }
            }
        }
    
        void setReturnCode(int ret) {
            if (mRet == PackageManager.INSTALL_SUCCEEDED) {
                // Only update mRet if it was previously INSTALL_SUCCEEDED to
                // ensure we do not overwrite any previous failure results.
                mRet = ret;
            }
        }
    
        void handleVerificationFinished() {
            if (!mVerificationCompleted) {
                mVerificationCompleted = true;
                if (mIntegrityVerificationCompleted) {
                    handleReturnCode();
                }
                // integrity verification still pending.
            }
        }
    
        void handleIntegrityVerificationFinished() {
            if (!mIntegrityVerificationCompleted) {
                mIntegrityVerificationCompleted = true;
                if (mVerificationCompleted) {
                    handleReturnCode();
                }
                // verifier still pending
            }
        }
    
    
        void handleRollbackEnabled() {
            // TODO(ruhler) b/112431924: Consider halting the install if we
            // couldn't enable rollback.
            mEnableRollbackCompleted = true;
            handleReturnCode();
        }
    
        @Override
        void handleReturnCode() {
            if (mVerificationCompleted
                    && mIntegrityVerificationCompleted && mEnableRollbackCompleted) {
                if ((installFlags & PackageManager.INSTALL_DRY_RUN) != 0) {
                    String packageName = "";
                    ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                            new ParseTypeImpl(
                                (changeId, packageName1, targetSdkVersion) -> {
                                    ApplicationInfo appInfo = new ApplicationInfo();
                                    appInfo.packageName = packageName1;
                                    appInfo.targetSdkVersion = targetSdkVersion;
                                    return mPackageParserCallback.isChangeEnabled(changeId,
                                            appInfo);
                                }).reset(),
                            origin.file, 0);
                    if (result.isError()) {
                        Slog.e(TAG, "Can't parse package at " + origin.file.getAbsolutePath(),
                                result.getException());
                    } else {
                        packageName = result.getResult().packageName;
                    }
                    try {
                        observer.onPackageInstalled(packageName, mRet, "Dry run", new Bundle());
                    } catch (RemoteException e) {
                        Slog.i(TAG, "Observer no longer exists.");
                    }
                    return;
                }
                if (mRet == PackageManager.INSTALL_SUCCEEDED) {
                    mRet = mArgs.copyApk();
                }
                processPendingInstall(mArgs, mRet);
                    }
        }
    }

    private void processPendingInstall(final InstallArgs args, final int currentStatus) {
        if (args.mMultiPackageInstallParams != null) {
            args.mMultiPackageInstallParams.tryProcessInstallRequest(args, currentStatus);
        } else {
            PackageInstalledInfo res = createPackageInstalledInfo(currentStatus);
            processInstallRequestsAsync(
                    res.returnCode == PackageManager.INSTALL_SUCCEEDED,
                    Collections.singletonList(new InstallRequest(args, res)));
        }
    }

    private void processInstallRequestsAsync(boolean success,
            List<InstallRequest> installRequests) {
        mHandler.post(() -> {
            if (success) {
                for (InstallRequest request : installRequests) {
                    request.args.doPreInstall(request.installResult.returnCode);
                }
                synchronized (mInstallLock) {
                    installPackagesTracedLI(installRequests);
                }
                for (InstallRequest request : installRequests) {
                    request.args.doPostInstall(
                            request.installResult.returnCode, request.installResult.uid);
                }
            }
            for (InstallRequest request : installRequests) {
                restoreAndPostInstall(request.args.user.getIdentifier(), request.installResult,
                        new PostInstallData(request.args, request.installResult, null));
            }
        });
    }

    private void installPackagesTracedLI(List<InstallRequest> requests) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installPackages");
            installPackagesLI(requests);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }
    private void installPackagesLI(List<InstallRequest> requests) {
        final Map<String, ScanResult> preparedScans = new ArrayMap<>(requests.size());
        final Map<String, InstallArgs> installArgs = new ArrayMap<>(requests.size());
        final Map<String, PackageInstalledInfo> installResults = new ArrayMap<>(requests.size());
        final Map<String, PrepareResult> prepareResults = new ArrayMap<>(requests.size());
        final Map<String, VersionInfo> versionInfos = new ArrayMap<>(requests.size());
        final Map<String, PackageSetting> lastStaticSharedLibSettings =
                new ArrayMap<>(requests.size());
        final Map<String, Boolean> createdAppId = new ArrayMap<>(requests.size());
        boolean success = false;
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installPackagesLI");
            for (InstallRequest request : requests) {
                // TODO(b/109941548): remove this once we've pulled everything from it and into
                //                    scan, reconcile or commit.
                final PrepareResult prepareResult;
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "preparePackage");
                    prepareResult =
                            preparePackageLI(request.args, request.installResult);
                } catch (PrepareFailure prepareFailure) {
                    request.installResult.setError(prepareFailure.error,
                            prepareFailure.getMessage());
                    request.installResult.origPackage = prepareFailure.conflictingPackage;
                    request.installResult.origPermission = prepareFailure.conflictingPermission;
                    return;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                request.installResult.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
                request.installResult.installerPackageName =
                        request.args.installSource.installerPackageName;

                final String packageName = prepareResult.packageToScan.getPackageName();
                prepareResults.put(packageName, prepareResult);
                installResults.put(packageName, request.installResult);
                installArgs.put(packageName, request.args);
                try {
                    final ScanResult result = scanPackageTracedLI(
                            prepareResult.packageToScan, prepareResult.parseFlags,
                            prepareResult.scanFlags, System.currentTimeMillis(),
                            request.args.user, request.args.abiOverride);
                    if (null != preparedScans.put(result.pkgSetting.pkg.getPackageName(), result)) {
                        request.installResult.setError(
                                PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Duplicate package " + result.pkgSetting.pkg.getPackageName()
                                        + " in multi-package install request.");
                        return;
                    }
                    createdAppId.put(packageName, optimisticallyRegisterAppId(result));
                    versionInfos.put(result.pkgSetting.pkg.getPackageName(),
                            getSettingsVersionForPackage(result.pkgSetting.pkg));
                    if (result.staticSharedLibraryInfo != null) {
                        final PackageSetting sharedLibLatestVersionSetting =
                                getSharedLibLatestVersionSetting(result);
                        if (sharedLibLatestVersionSetting != null) {
                            lastStaticSharedLibSettings.put(result.pkgSetting.pkg.getPackageName(),
                                    sharedLibLatestVersionSetting);
                        }
                    }
                } catch (PackageManagerException e) {
                    request.installResult.setError("Scanning Failed.", e);
                    return;
                }
            }
            ReconcileRequest reconcileRequest = new ReconcileRequest(preparedScans, installArgs,
                    installResults,
                    prepareResults,
                    mSharedLibraries,
                    Collections.unmodifiableMap(mPackages), versionInfos,
                    lastStaticSharedLibSettings);
            CommitRequest commitRequest = null;
            synchronized (mLock) {
                Map<String, ReconciledPackage> reconciledPackages;
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "reconcilePackages");
                    reconciledPackages = reconcilePackagesLocked(
                            reconcileRequest, mSettings.mKeySetManagerService);
                } catch (ReconcileFailure e) {
                    for (InstallRequest request : requests) {
                        request.installResult.setError("Reconciliation failed...", e);
                    }
                    return;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "commitPackages");
                    commitRequest = new CommitRequest(reconciledPackages,
                            mUserManager.getUserIds());
                    commitPackagesLocked(commitRequest);
                    success = true;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
            executePostCommitSteps(commitRequest);
        } finally {
            if (success) {
                for (InstallRequest request : requests) {
                    final InstallArgs args = request.args;
                    if (args.mDataLoaderType != DataLoaderType.INCREMENTAL) {
                        continue;
                    }
                    if (args.signingDetails.signatureSchemeVersion != SIGNING_BLOCK_V4) {
                        continue;
                    }
                    // For incremental installs, we bypass the verifier prior to install. Now
                    // that we know the package is valid, send a notice to the verifier with
                    // the root hash of the base.apk.
                    final String baseCodePath = request.installResult.pkg.getBaseCodePath();
                    final String[] splitCodePaths = request.installResult.pkg.getSplitCodePaths();
                    final Uri originUri = Uri.fromFile(args.origin.resolvedFile);
                    final int verificationId = mPendingVerificationToken++;
                    final String rootHashString = PackageManagerServiceUtils
                            .buildVerificationRootHashString(baseCodePath, splitCodePaths);
                    broadcastPackageVerified(verificationId, originUri,
                            PackageManager.VERIFICATION_ALLOW, rootHashString,
                            args.mDataLoaderType, args.getUser());
                }
            } else {
                for (ScanResult result : preparedScans.values()) {
                    if (createdAppId.getOrDefault(result.request.parsedPackage.getPackageName(),
                            false)) {
                        cleanUpAppIdCreation(result);
                    }
                }
                // TODO(patb): create a more descriptive reason than unknown in future release
                // mark all non-failure installs as UNKNOWN so we do not treat them as success
                for (InstallRequest request : requests) {
                    if (request.installResult.freezer != null) {
                        request.installResult.freezer.close();
                    }
                    if (request.installResult.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                        request.installResult.returnCode = PackageManager.INSTALL_UNKNOWN;
                    }
                }
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private PrepareResult preparePackageLI(InstallArgs args, PackageInstalledInfo res)
            throws PrepareFailure {
        final int installFlags = args.installFlags;
        final File tmpPackageFile = new File(args.getCodePath());
        final boolean onExternal = args.volumeUuid != null;
        final boolean instantApp = ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0);
        final boolean fullApp = ((installFlags & PackageManager.INSTALL_FULL_APP) != 0);
        final boolean virtualPreload =
                ((installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);
        @ScanFlags int scanFlags = SCAN_NEW_INSTALL | SCAN_UPDATE_SIGNATURE;
        if (args.move != null) {
            // moving a complete application; perform an initial scan on the new install location
            scanFlags |= SCAN_INITIAL;
        }
        if ((installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
            scanFlags |= SCAN_DONT_KILL_APP;
        }
        if (instantApp) {
            scanFlags |= SCAN_AS_INSTANT_APP;
        }
        if (fullApp) {
            scanFlags |= SCAN_AS_FULL_APP;
        }
        if (virtualPreload) {
            scanFlags |= SCAN_AS_VIRTUAL_PRELOAD;
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "installPackageLI: path=" + tmpPackageFile);

        // Sanity check
        if (instantApp && onExternal) {
            Slog.i(TAG, "Incompatible ephemeral install; external=" + onExternal);
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_INSTANT_APP_INVALID);
        }

        // Retrieve PackageSettings and parse package
        @ParseFlags final int parseFlags = mDefParseFlags | PackageParser.PARSE_CHATTY
                | PackageParser.PARSE_ENFORCE_CODE
                | (onExternal ? PackageParser.PARSE_EXTERNAL_STORAGE : 0);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parsePackage");
        ParsedPackage parsedPackage;
        try (PackageParser2 pp = new PackageParser2(mSeparateProcesses, false, mMetrics, null,
                mPackageParserCallback)) {
            parsedPackage = pp.parsePackage(tmpPackageFile, parseFlags, false);
            AndroidPackageUtils.validatePackageDexMetadata(parsedPackage);
        } catch (PackageParserException e) {
            throw new PrepareFailure("Failed parse during installPackageLI", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // Instant apps have several additional install-time checks.
        if (instantApp) {
            if (parsedPackage.getTargetSdkVersion() < Build.VERSION_CODES.O) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                                + " does not target at least O");
                throw new PrepareFailure(INSTALL_FAILED_INSTANT_APP_INVALID,
                        "Instant app package must target at least O");
            }
            if (parsedPackage.getSharedUserId() != null) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                        + " may not declare sharedUserId.");
                throw new PrepareFailure(INSTALL_FAILED_INSTANT_APP_INVALID,
                        "Instant app package may not declare a sharedUserId");
            }
        }

        if (parsedPackage.isStaticSharedLibrary()) {
            // Static shared libraries have synthetic package names
            renameStaticSharedLibraryPackage(parsedPackage);

            // No static shared libs on external storage
            if (onExternal) {
                Slog.i(TAG, "Static shared libs can only be installed on internal storage.");
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Packages declaring static-shared libs cannot be updated");
            }
        }

        String pkgName = res.name = parsedPackage.getPackageName();
        if (parsedPackage.isTestOnly()) {
            if ((installFlags & PackageManager.INSTALL_ALLOW_TEST) == 0) {
                throw new PrepareFailure(INSTALL_FAILED_TEST_ONLY, "installPackageLI");
            }
        }

        try {
            // either use what we've been given or parse directly from the APK
            if (args.signingDetails != PackageParser.SigningDetails.UNKNOWN) {
                parsedPackage.setSigningDetails(args.signingDetails);
            } else {
                parsedPackage.setSigningDetails(
                        ParsingPackageUtils.getSigningDetails(parsedPackage, false /* skipVerify */));
            }
        } catch (PackageParserException e) {
            throw new PrepareFailure("Failed collect during installPackageLI", e);
        }

        if (instantApp && parsedPackage.getSigningDetails().signatureSchemeVersion
                < SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                    + " is not signed with at least APK Signature Scheme v2");
            throw new PrepareFailure(INSTALL_FAILED_INSTANT_APP_INVALID,
                    "Instant app package must be signed with APK Signature Scheme v2 or greater");
        }

        boolean systemApp = false;
        boolean replace = false;
        synchronized (mLock) {
            // Check if installing already existing package
            if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                String oldName = mSettings.getRenamedPackageLPr(pkgName);
                if (parsedPackage.getOriginalPackages().contains(oldName)
                        && mPackages.containsKey(oldName)) {
                    // This package is derived from an original package,
                    // and this device has been updating from that original
                    // name.  We must continue using the original name, so
                    // rename the new package here.
                    parsedPackage.setPackageName(oldName);
                    pkgName = parsedPackage.getPackageName();
                    replace = true;
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Replacing existing renamed package: oldName="
                                + oldName + " pkgName=" + pkgName);
                    }
                } else if (mPackages.containsKey(pkgName)) {
                    // This package, under its official name, already exists
                    // on the device; we should replace it.
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replace existing pacakge: " + pkgName);
                }

                if (replace) {
                    // Prevent apps opting out from runtime permissions
                    AndroidPackage oldPackage = mPackages.get(pkgName);
                    final int oldTargetSdk = oldPackage.getTargetSdkVersion();
                    final int newTargetSdk = parsedPackage.getTargetSdkVersion();
                    if (oldTargetSdk > Build.VERSION_CODES.LOLLIPOP_MR1
                            && newTargetSdk <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        throw new PrepareFailure(
                                PackageManager.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
                                "Package " + parsedPackage.getPackageName()
                                        + " new target SDK " + newTargetSdk
                                        + " doesn't support runtime permissions but the old"
                                        + " target SDK " + oldTargetSdk + " does.");
                    }
                    // Prevent persistent apps from being updated
                    if (oldPackage.isPersistent()
                            && ((installFlags & PackageManager.INSTALL_STAGED) == 0)) {
                        throw new PrepareFailure(PackageManager.INSTALL_FAILED_INVALID_APK,
                                "Package " + oldPackage.getPackageName() + " is a persistent app. "
                                        + "Persistent apps are not updateable.");
                    }
                }
            }

            PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Existing package: " + ps);

                // Static shared libs have same package with different versions where
                // we internally use a synthetic package name to allow multiple versions
                // of the same package, therefore we need to compare signatures against
                // the package setting for the latest library version.
                PackageSetting signatureCheckPs = ps;
                if (parsedPackage.isStaticSharedLibrary()) {
                    SharedLibraryInfo libraryInfo = getLatestSharedLibraVersionLPr(parsedPackage);
                    if (libraryInfo != null) {
                        signatureCheckPs = mSettings.getPackageLPr(libraryInfo.getPackageName());
                    }
                }

                // Quick sanity check that we're signed correctly if updating;
                // we'll check this again later when scanning, but we want to
                // bail early here before tripping over redefined permissions.
                final KeySetManagerService ksms = mSettings.mKeySetManagerService;
                if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
                    if (!ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                        throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Package "
                                + parsedPackage.getPackageName() + " upgrade keys do not match the "
                                + "previously installed version");
                    }
                } else {
                    try {
                        final boolean compareCompat = isCompatSignatureUpdateNeeded(parsedPackage);
                        final boolean compareRecover = isRecoverSignatureUpdateNeeded(
                                parsedPackage);
                        // We don't care about disabledPkgSetting on install for now.
                        final boolean compatMatch = verifySignatures(signatureCheckPs, null,
                                parsedPackage.getSigningDetails(), compareCompat, compareRecover);
                        // The new KeySets will be re-added later in the scanning process.
                        if (compatMatch) {
                            synchronized (mLock) {
                                ksms.removeAppKeySetDataLPw(parsedPackage.getPackageName());
                            }
                        }
                    } catch (PackageManagerException e) {
                        throw new PrepareFailure(e.error, e.getMessage());
                    }
                }

                if (ps.pkg != null) {
                    systemApp = ps.pkg.isSystem();
                }
                res.origUsers = ps.queryInstalledUsers(mUserManager.getUserIds(), true);
            }


            int N = ArrayUtils.size(parsedPackage.getPermissions());
            for (int i = N - 1; i >= 0; i--) {
                final ParsedPermission perm = parsedPackage.getPermissions().get(i);
                final BasePermission bp = mPermissionManager.getPermissionTEMP(perm.getName());

                // Don't allow anyone but the system to define ephemeral permissions.
                if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0
                        && !systemApp) {
                    Slog.w(TAG, "Non-System package " + parsedPackage.getPackageName()
                            + " attempting to delcare ephemeral permission "
                            + perm.getName() + "; Removing ephemeral.");
                    perm.setProtectionLevel(perm.getProtectionLevel() & ~PermissionInfo.PROTECTION_FLAG_INSTANT);
                }

                // Check whether the newly-scanned package wants to define an already-defined perm
                if (bp != null) {
                    // If the defining package is signed with our cert, it's okay.  This
                    // also includes the "updating the same package" case, of course.
                    // "updating same package" could also involve key-rotation.
                    final boolean sigsOk;
                    final String sourcePackageName = bp.getSourcePackageName();
                    final PackageSetting sourcePackageSetting;
                    synchronized (mLock) {
                        sourcePackageSetting = mSettings.getPackageLPr(sourcePackageName);
                    }
                    final SigningDetails sourceSigningDetails = (sourcePackageSetting == null
                            ? SigningDetails.UNKNOWN : sourcePackageSetting.getSigningDetails());
                    final KeySetManagerService ksms = mSettings.mKeySetManagerService;
                    if (sourcePackageName.equals(parsedPackage.getPackageName())
                            && (ksms.shouldCheckUpgradeKeySetLocked(
                            sourcePackageSetting, scanFlags))) {
                        sigsOk = ksms.checkUpgradeKeySetLocked(sourcePackageSetting, parsedPackage);
                    } else {

                        // in the event of signing certificate rotation, we need to see if the
                        // package's certificate has rotated from the current one, or if it is an
                        // older certificate with which the current is ok with sharing permissions
                        if (sourceSigningDetails.checkCapability(
                                parsedPackage.getSigningDetails(),
                                PackageParser.SigningDetails.CertCapabilities.PERMISSION)) {
                            sigsOk = true;
                        } else if (parsedPackage.getSigningDetails().checkCapability(
                                sourceSigningDetails,
                                PackageParser.SigningDetails.CertCapabilities.PERMISSION)) {
                            // the scanned package checks out, has signing certificate rotation
                            // history, and is newer; bring it over
                            synchronized (mLock) {
                                sourcePackageSetting.signatures.mSigningDetails =
                                        parsedPackage.getSigningDetails();
                            }
                            sigsOk = true;
                        } else {
                            sigsOk = false;
                        }
                    }
                    if (!sigsOk) {
                        // If the owning package is the system itself, we log but allow
                        // install to proceed; we fail the install on all other permission
                        // redefinitions.
                        if (!sourcePackageName.equals("android")) {
                            throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PERMISSION, "Package "
                                    + parsedPackage.getPackageName()
                                    + " attempting to redeclare permission "
                                    + perm.getName() + " already owned by "
                                    + sourcePackageName)
                                    .conflictsWithExistingPermission(perm.getName(),
                                            sourcePackageName);
                        } else {
                            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                    + " attempting to redeclare system permission "
                                    + perm.getName() + "; ignoring new declaration");
                            parsedPackage.removePermission(i);
                        }
                    } else if (!PLATFORM_PACKAGE_NAME.equals(parsedPackage.getPackageName())) {
                        // Prevent apps to change protection level to dangerous from any other
                        // type as this would allow a privilege escalation where an app adds a
                        // normal/signature permission in other app's group and later redefines
                        // it as dangerous leading to the group auto-grant.
                        if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_MASK_BASE)
                                == PermissionInfo.PROTECTION_DANGEROUS) {
                            if (bp != null && !bp.isRuntime()) {
                                Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                        + " trying to change a non-runtime permission "
                                        + perm.getName()
                                        + " to runtime; keeping old protection level");
                                perm.setProtectionLevel(bp.getProtectionLevel());
                            }
                        }
                    }
                }
            }
        }

        if (systemApp) {
            if (onExternal) {
                // Abort update; system app can't be replaced with app on sdcard
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Cannot install updates to system apps on sdcard");
            } else if (instantApp) {
                // Abort update; system app can't be replaced with an instant app
                throw new PrepareFailure(INSTALL_FAILED_INSTANT_APP_INVALID,
                        "Cannot update a system app with an instant app");
            }
        }

        if (args.move != null) {
            // We did an in-place move, so dex is ready to roll
            scanFlags |= SCAN_NO_DEX;
            scanFlags |= SCAN_MOVE;

            synchronized (mLock) {
                final PackageSetting ps = mSettings.mPackages.get(pkgName);
                if (ps == null) {
                    res.setError(INSTALL_FAILED_INTERNAL_ERROR,
                            "Missing settings for moved package " + pkgName);
                }

                // We moved the entire application as-is, so bring over the
                // previously derived ABI information.
                parsedPackage.setPrimaryCpuAbi(ps.primaryCpuAbiString)
                        .setSecondaryCpuAbi(ps.secondaryCpuAbiString);
            }

        } else {
            // Enable SCAN_NO_DEX flag to skip dexopt at a later stage
            scanFlags |= SCAN_NO_DEX;

            try {
                final boolean extractNativeLibs = !AndroidPackageUtils.isLibrary(parsedPackage);
                PackageSetting pkgSetting;
                synchronized (mLock) {
                    pkgSetting = mSettings.getPackageLPr(pkgName);
                }
                String abiOverride =
                        (pkgSetting == null || TextUtils.isEmpty(pkgSetting.cpuAbiOverrideString)
                        ? args.abiOverride : pkgSetting.cpuAbiOverrideString);
                boolean isUpdatedSystemAppFromExistingSetting = pkgSetting != null
                        && pkgSetting.getPkgState().isUpdatedSystemApp();
                AndroidPackage oldPackage = mPackages.get(pkgName);
                boolean isUpdatedSystemAppInferred = oldPackage != null && oldPackage.isSystem();
                final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths>
                        derivedAbi = mInjector.getAbiHelper().derivePackageAbi(parsedPackage,
                        isUpdatedSystemAppFromExistingSetting || isUpdatedSystemAppInferred,
                        abiOverride, extractNativeLibs);
                derivedAbi.first.applyTo(parsedPackage);
                derivedAbi.second.applyTo(parsedPackage);
            } catch (PackageManagerException pme) {
                Slog.e(TAG, "Error deriving application ABI", pme);
                throw new PrepareFailure(INSTALL_FAILED_INTERNAL_ERROR,
                        "Error deriving application ABI");
            }
        }

        if (!args.doRename(res.returnCode, parsedPackage)) {
            throw new PrepareFailure(INSTALL_FAILED_INSUFFICIENT_STORAGE, "Failed rename");
        }

        try {
            setUpFsVerityIfPossible(parsedPackage);
        } catch (InstallerException | IOException | DigestException | NoSuchAlgorithmException e) {
            throw new PrepareFailure(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to set up verity: " + e);
        }

        if (!instantApp) {
            startIntentFilterVerifications(args.user.getIdentifier(), replace, parsedPackage);
        } else {
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "Not verifying instant app install for app links: " + pkgName);
            }
        }
        final PackageFreezer freezer =
                freezePackageForInstall(pkgName, installFlags, "installPackageLI");
        boolean shouldCloseFreezerBeforeReturn = true;
        try {
            final AndroidPackage existingPackage;
            String renamedPackage = null;
            boolean sysPkg = false;
            int targetScanFlags = scanFlags;
            int targetParseFlags = parseFlags;
            final PackageSetting ps;
            final PackageSetting disabledPs;
            if (replace) {
                if (parsedPackage.isStaticSharedLibrary()) {
                    // Static libs have a synthetic package name containing the version
                    // and cannot be updated as an update would get a new package name,
                    // unless this is the exact same version code which is useful for
                    // development.
                    AndroidPackage existingPkg = mPackages.get(parsedPackage.getPackageName());
                    if (existingPkg != null
                            && existingPkg.getLongVersionCode()
                            != parsedPackage.getLongVersionCode()) {
                        throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Packages declaring "
                                        + "static-shared libs cannot be updated");
                    }
                }

                final boolean isInstantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;

                final AndroidPackage oldPackage;
                final String pkgName11 = parsedPackage.getPackageName();
                final int[] allUsers;
                final int[] installedUsers;
                final int[] uninstalledUsers;

                synchronized (mLock) {
                    oldPackage = mPackages.get(pkgName11);
                    existingPackage = oldPackage;
                    if (DEBUG_INSTALL) {
                        // TODO(b/135203078): PackageImpl.toString()
                        Slog.d(TAG,
                                "replacePackageLI: new=" + parsedPackage + ", old=" + oldPackage);
                    }

                    ps = mSettings.mPackages.get(pkgName11);
                    disabledPs = mSettings.getDisabledSystemPkgLPr(ps);

                    // verify signatures are valid
                    final KeySetManagerService ksms = mSettings.mKeySetManagerService;
                    if (ksms.shouldCheckUpgradeKeySetLocked(ps, scanFlags)) {
                        if (!ksms.checkUpgradeKeySetLocked(ps, parsedPackage)) {
                            throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                    "New package not signed by keys specified by upgrade-keysets: "
                                            + pkgName11);
                        }
                    } else {
                        // default to original signature matching
                        if (!parsedPackage.getSigningDetails().checkCapability(
                                oldPackage.getSigningDetails(),
                                SigningDetails.CertCapabilities.INSTALLED_DATA)
                                && !oldPackage.getSigningDetails().checkCapability(
                                parsedPackage.getSigningDetails(),
                                SigningDetails.CertCapabilities.ROLLBACK)) {
                            throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                    "New package has a different signature: " + pkgName11);
                        }
                    }

                    // don't allow a system upgrade unless the upgrade hash matches
                    if (oldPackage.getRestrictUpdateHash() != null && oldPackage.isSystem()) {
                        final byte[] digestBytes;
                        try {
                            final MessageDigest digest = MessageDigest.getInstance("SHA-512");
                            updateDigest(digest, new File(parsedPackage.getBaseCodePath()));
                            if (!ArrayUtils.isEmpty(parsedPackage.getSplitCodePaths())) {
                                for (String path : parsedPackage.getSplitCodePaths()) {
                                    updateDigest(digest, new File(path));
                                }
                            }
                            digestBytes = digest.digest();
                        } catch (NoSuchAlgorithmException | IOException e) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "Could not compute hash: " + pkgName11);
                        }
                        if (!Arrays.equals(oldPackage.getRestrictUpdateHash(), digestBytes)) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "New package fails restrict-update check: " + pkgName11);
                        }
                        // retain upgrade restriction
                        parsedPackage.setRestrictUpdateHash(oldPackage.getRestrictUpdateHash());
                    }

                    // Check for shared user id changes
                    String invalidPackageName = null;
                    if (!Objects.equals(oldPackage.getSharedUserId(),
                            parsedPackage.getSharedUserId())) {
                        invalidPackageName = parsedPackage.getPackageName();
                    }

                    if (invalidPackageName != null) {
                        throw new PrepareFailure(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                                "Package " + invalidPackageName + " tried to change user "
                                        + oldPackage.getSharedUserId());
                    }

                    // In case of rollback, remember per-user/profile install state
                    allUsers = mUserManager.getUserIds();
                    installedUsers = ps.queryInstalledUsers(allUsers, true);
                    uninstalledUsers = ps.queryInstalledUsers(allUsers, false);


                    // don't allow an upgrade from full to ephemeral
                    if (isInstantApp) {
                        if (args.user == null || args.user.getIdentifier() == UserHandle.USER_ALL) {
                            for (int currentUser : allUsers) {
                                if (!ps.getInstantApp(currentUser)) {
                                    // can't downgrade from full to instant
                                    Slog.w(TAG,
                                            "Can't replace full app with instant app: " + pkgName11
                                                    + " for user: " + currentUser);
                                    throw new PrepareFailure(
                                            PackageManager.INSTALL_FAILED_INSTANT_APP_INVALID);
                                }
                            }
                        } else if (!ps.getInstantApp(args.user.getIdentifier())) {
                            // can't downgrade from full to instant
                            Slog.w(TAG, "Can't replace full app with instant app: " + pkgName11
                                    + " for user: " + args.user.getIdentifier());
                            throw new PrepareFailure(
                                    PackageManager.INSTALL_FAILED_INSTANT_APP_INVALID);
                        }
                    }
                }

                // Update what is removed
                res.removedInfo = new PackageRemovedInfo(this);
                res.removedInfo.uid = oldPackage.getUid();
                res.removedInfo.removedPackage = oldPackage.getPackageName();
                res.removedInfo.installerPackageName = ps.installSource.installerPackageName;
                res.removedInfo.isStaticSharedLib = parsedPackage.getStaticSharedLibName() != null;
                res.removedInfo.isUpdate = true;
                res.removedInfo.origUsers = installedUsers;
                res.removedInfo.installReasons = new SparseArray<>(installedUsers.length);
                for (int i = 0; i < installedUsers.length; i++) {
                    final int userId = installedUsers[i];
                    res.removedInfo.installReasons.put(userId, ps.getInstallReason(userId));
                }
                res.removedInfo.uninstallReasons = new SparseArray<>(uninstalledUsers.length);
                for (int i = 0; i < uninstalledUsers.length; i++) {
                    final int userId = uninstalledUsers[i];
                    res.removedInfo.uninstallReasons.put(userId, ps.getUninstallReason(userId));
                }

                sysPkg = oldPackage.isSystem();
                if (sysPkg) {
                    // Set the system/privileged/oem/vendor/product flags as needed
                    final boolean privileged = oldPackage.isPrivileged();
                    final boolean oem = oldPackage.isOem();
                    final boolean vendor = oldPackage.isVendor();
                    final boolean product = oldPackage.isProduct();
                    final boolean odm = oldPackage.isOdm();
                    final boolean systemExt = oldPackage.isSystemExt();
                    final @ParseFlags int systemParseFlags = parseFlags;
                    final @ScanFlags int systemScanFlags = scanFlags
                            | SCAN_AS_SYSTEM
                            | (privileged ? SCAN_AS_PRIVILEGED : 0)
                            | (oem ? SCAN_AS_OEM : 0)
                            | (vendor ? SCAN_AS_VENDOR : 0)
                            | (product ? SCAN_AS_PRODUCT : 0)
                            | (odm ? SCAN_AS_ODM : 0)
                            | (systemExt ? SCAN_AS_SYSTEM_EXT : 0);

                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "replaceSystemPackageLI: new=" + parsedPackage
                                + ", old=" + oldPackage);
                    }
                    res.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
                    targetParseFlags = systemParseFlags;
                    targetScanFlags = systemScanFlags;
                } else { // non system replace
                    replace = true;
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG,
                                "replaceNonSystemPackageLI: new=" + parsedPackage + ", old="
                                        + oldPackage);
                    }
                }
            } else { // new package install
                ps = null;
                disabledPs = null;
                replace = false;
                existingPackage = null;
                // Remember this for later, in case we need to rollback this install
                String pkgName1 = parsedPackage.getPackageName();

                if (DEBUG_INSTALL) Slog.d(TAG, "installNewPackageLI: " + parsedPackage);

                // TODO(patb): MOVE TO RECONCILE
                synchronized (mLock) {
                    renamedPackage = mSettings.getRenamedPackageLPr(pkgName1);
                    if (renamedPackage != null) {
                        // A package with the same name is already installed, though
                        // it has been renamed to an older name.  The package we
                        // are trying to install should be installed as an update to
                        // the existing one, but that has not been requested, so bail.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling package running as "
                                        + renamedPackage);
                    }
                    if (mPackages.containsKey(pkgName1)) {
                        // Don't allow installation over an existing package with the same name.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling.");
                    }
                }
            }
            // we're passing the freezer back to be closed in a later phase of install
            shouldCloseFreezerBeforeReturn = false;

            return new PrepareResult(replace, targetScanFlags, targetParseFlags,
                    existingPackage, parsedPackage, replace /* clearCodeCache */, sysPkg,
                    ps, disabledPs);
        } finally {
            res.freezer = freezer;
            if (shouldCloseFreezerBeforeReturn) {
                freezer.close();
            }
        }
    }
    class FileInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        // Example topology:
        // /data/app/com.example/base.apk
        // /data/app/com.example/split_foo.apk
        // /data/app/com.example/lib/arm/libfoo.so
        // /data/app/com.example/lib/arm64/libfoo.so
        // /data/app/com.example/dalvik/arm/base.apk@classes.dex

        /** New install */
        FileInstallArgs(InstallParams params) {
            super(params);
        }

        /** Existing install */
        FileInstallArgs(String codePath, String resourcePath, String[] instructionSets) {
            super(OriginInfo.fromNothing(), null, null, 0, InstallSource.EMPTY,
                    null, null, instructionSets, null, null, null, MODE_DEFAULT, null, 0,
                    PackageParser.SigningDetails.UNKNOWN,
                    PackageManager.INSTALL_REASON_UNKNOWN, false, null /* parent */,
                    DataLoaderType.NONE);
            this.codeFile = (codePath != null) ? new File(codePath) : null;
            this.resourceFile = (resourcePath != null) ? new File(resourcePath) : null;
        }

        int copyApk() {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyApk");
            try {
                return doCopyApk();
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }

        private int doCopyApk() {
            if (origin.staged) {
                if (DEBUG_INSTALL) Slog.d(TAG, origin.file + " already staged; skipping copy");
                codeFile = origin.file;
                resourceFile = origin.file;
                return PackageManager.INSTALL_SUCCEEDED;
            }

            try {
                final boolean isEphemeral = (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
                final File tempDir =
                        mInstallerService.allocateStageDirLegacy(volumeUuid, isEphemeral);
                codeFile = tempDir;
                resourceFile = tempDir;
            } catch (IOException e) {
                Slog.w(TAG, "Failed to create copy file: " + e);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }

            int ret = PackageManagerServiceUtils.copyPackage(
                    origin.file.getAbsolutePath(), codeFile);
            if (ret != PackageManager.INSTALL_SUCCEEDED) {
                Slog.e(TAG, "Failed to copy package");
                return ret;
            }

            final boolean isIncremental = isIncrementalPath(codeFile.getAbsolutePath());
            final File libraryRoot = new File(codeFile, LIB_DIR_NAME);
            NativeLibraryHelper.Handle handle = null;
            try {
                handle = NativeLibraryHelper.Handle.create(codeFile);
                ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                        abiOverride, isIncremental);
            } catch (IOException e) {
                Slog.e(TAG, "Copying native libraries failed", e);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            } finally {
                IoUtils.closeQuietly(handle);
            }

            return ret;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        @Override
        boolean doRename(int status, ParsedPackage parsedPackage) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
                return false;
            }

            final File targetDir = codeFile.getParentFile();
            final File beforeCodeFile = codeFile;
            final File afterCodeFile = getNextCodePath(targetDir, parsedPackage.getPackageName());

            if (DEBUG_INSTALL) Slog.d(TAG, "Renaming " + beforeCodeFile + " to " + afterCodeFile);
            final boolean onIncremental = mIncrementalManager != null
                    && isIncrementalPath(beforeCodeFile.getAbsolutePath());
            try {
                makeDirRecursive(afterCodeFile.getParentFile(), 0775);
                if (onIncremental) {
                    mIncrementalManager.renameCodePath(beforeCodeFile, afterCodeFile);
                } else {
                    Os.rename(beforeCodeFile.getAbsolutePath(), afterCodeFile.getAbsolutePath());
                }
            } catch (IOException | ErrnoException e) {
                Slog.w(TAG, "Failed to rename", e);
                return false;
            }

            //TODO(b/136132412): enable selinux restorecon for incremental directories
            if (!onIncremental && !SELinux.restoreconRecursive(afterCodeFile)) {
                Slog.w(TAG, "Failed to restorecon");
                return false;
            }

            // Reflect the rename internally
            codeFile = afterCodeFile;
            resourceFile = afterCodeFile;

            // Reflect the rename in scanned details
            try {
                parsedPackage.setCodePath(afterCodeFile.getCanonicalPath());
            } catch (IOException e) {
                Slog.e(TAG, "Failed to get path: " + afterCodeFile, e);
                return false;
            }
            parsedPackage.setBaseCodePath(FileUtils.rewriteAfterRename(beforeCodeFile,
                    afterCodeFile, parsedPackage.getBaseCodePath()));
            parsedPackage.setSplitCodePaths(FileUtils.rewriteAfterRename(beforeCodeFile,
                    afterCodeFile, parsedPackage.getSplitCodePaths()));

            return true;
        }

        int doPostInstall(int status, int uid) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        @Override
        String getCodePath() {
            return (codeFile != null) ? codeFile.getAbsolutePath() : null;
        }

        @Override
        String getResourcePath() {
            return (resourceFile != null) ? resourceFile.getAbsolutePath() : null;
        }

        private boolean cleanUp() {
            if (codeFile == null || !codeFile.exists()) {
                return false;
            }

            String codePath = codeFile.getAbsolutePath();
            if (mIncrementalManager != null && isIncrementalPath(codePath)) {
                mIncrementalManager.closeStorage(codePath);
            }

            removeCodePathLI(codeFile);

            if (resourceFile != null && !FileUtils.contains(codeFile, resourceFile)) {
                resourceFile.delete();
            }

            return true;
        }

        void cleanUpResourcesLI() {
            // Try enumerating all code paths before deleting
            List<String> allCodePaths = Collections.EMPTY_LIST;
            if (codeFile != null && codeFile.exists()) {
                try {
                    final PackageLite pkg = PackageParser.parsePackageLite(codeFile, 0);
                    allCodePaths = pkg.getAllCodePaths();
                } catch (PackageParserException e) {
                    // Ignored; we tried our best
                }
            }

            cleanUp();
            removeDexFiles(allCodePaths, instructionSets);
        }

        boolean doPostDeleteLI(boolean delete) {
            // XXX err, shouldn't we respect the delete flag?
            cleanUpResourcesLI();
            return true;
        }
    }

    private boolean optimisticallyRegisterAppId(@NonNull ScanResult result)
            throws PackageManagerException {
        if (!result.existingSettingCopied) {
            // THROWS: when we can't allocate a user id. add call to check if there's
            // enough space to ensure we won't throw; otherwise, don't modify state
            return mSettings.registerAppIdLPw(result.pkgSetting);
        }
        return false;
    }

    private static Map<String, ReconciledPackage> reconcilePackagesLocked(
            final ReconcileRequest request, KeySetManagerService ksms)
            throws ReconcileFailure {
        final Map<String, ScanResult> scannedPackages = request.scannedPackages;

        final Map<String, ReconciledPackage> result = new ArrayMap<>(scannedPackages.size());

        // make a copy of the existing set of packages so we can combine them with incoming packages
        final ArrayMap<String, AndroidPackage> combinedPackages =
                new ArrayMap<>(request.allPackages.size() + scannedPackages.size());

        combinedPackages.putAll(request.allPackages);

        final Map<String, LongSparseArray<SharedLibraryInfo>> incomingSharedLibraries =
                new ArrayMap<>();

        for (String installPackageName : scannedPackages.keySet()) {
            final ScanResult scanResult = scannedPackages.get(installPackageName);

            // add / replace existing with incoming packages
            combinedPackages.put(scanResult.pkgSetting.name, scanResult.request.parsedPackage);

            // in the first pass, we'll build up the set of incoming shared libraries
            final List<SharedLibraryInfo> allowedSharedLibInfos =
                    getAllowedSharedLibInfos(scanResult, request.sharedLibrarySource);
            final SharedLibraryInfo staticLib = scanResult.staticSharedLibraryInfo;
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!addSharedLibraryToPackageVersionMap(incomingSharedLibraries, info)) {
                        throw new ReconcileFailure("Static Shared Library " + staticLib.getName()
                                + " is being installed twice in this set!");
                    }
                }
            }

            // the following may be null if we're just reconciling on boot (and not during install)
            final InstallArgs installArgs = request.installArgs.get(installPackageName);
            final PackageInstalledInfo res = request.installResults.get(installPackageName);
            final PrepareResult prepareResult = request.preparedPackages.get(installPackageName);
            final boolean isInstall = installArgs != null;
            if (isInstall && (res == null || prepareResult == null)) {
                throw new ReconcileFailure("Reconcile arguments are not balanced for "
                        + installPackageName + "!");
            }

            final DeletePackageAction deletePackageAction;
            // we only want to try to delete for non system apps
            if (isInstall && prepareResult.replace && !prepareResult.system) {
                final boolean killApp = (scanResult.request.scanFlags & SCAN_DONT_KILL_APP) == 0;
                final int deleteFlags = PackageManager.DELETE_KEEP_DATA
                        | (killApp ? 0 : PackageManager.DELETE_DONT_KILL_APP);
                deletePackageAction = mayDeletePackageLocked(res.removedInfo,
                        prepareResult.originalPs, prepareResult.disabledPs,
                        deleteFlags, null /* all users */);
                if (deletePackageAction == null) {
                    throw new ReconcileFailure(
                            PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                            "May not delete " + installPackageName + " to replace");
                }
            } else {
                deletePackageAction = null;
            }

            final int scanFlags = scanResult.request.scanFlags;
            final int parseFlags = scanResult.request.parseFlags;
            final ParsedPackage parsedPackage = scanResult.request.parsedPackage;

            final PackageSetting disabledPkgSetting = scanResult.request.disabledPkgSetting;
            final PackageSetting lastStaticSharedLibSetting =
                    request.lastStaticSharedLibSettings.get(installPackageName);
            final PackageSetting signatureCheckPs =
                    (prepareResult != null && lastStaticSharedLibSetting != null)
                            ? lastStaticSharedLibSetting
                            : scanResult.pkgSetting;
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            SigningDetails signingDetails = null;
            if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
                if (ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                } else {
                    if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "Package " + parsedPackage.getPackageName()
                                        + " upgrade keys do not match the previously installed"
                                        + " version");
                    } else {
                        String msg = "System package " + parsedPackage.getPackageName()
                                + " signature changed; retaining data.";
                        reportSettingsProblem(Log.WARN, msg);
                    }
                }
                signingDetails = parsedPackage.getSigningDetails();
            } else {
                try {
                    final VersionInfo versionInfo = request.versionInfos.get(installPackageName);
                    final boolean compareCompat = isCompatSignatureUpdateNeeded(versionInfo);
                    final boolean compareRecover = isRecoverSignatureUpdateNeeded(versionInfo);
                    final boolean compatMatch = verifySignatures(signatureCheckPs,
                            disabledPkgSetting, parsedPackage.getSigningDetails(), compareCompat,
                            compareRecover);
                    // The new KeySets will be re-added later in the scanning process.
                    if (compatMatch) {
                        removeAppKeySetData = true;
                    }
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    signingDetails = parsedPackage.getSigningDetails();


                    // if this is is a sharedUser, check to see if the new package is signed by a
                    // newer
                    // signing certificate than the existing one, and if so, copy over the new
                    // details
                    if (signatureCheckPs.sharedUser != null) {
                        // Attempt to merge the existing lineage for the shared SigningDetails with
                        // the lineage of the new package; if the shared SigningDetails are not
                        // returned this indicates the new package added new signers to the lineage
                        // and/or changed the capabilities of existing signers in the lineage.
                        SigningDetails sharedSigningDetails =
                                signatureCheckPs.sharedUser.signatures.mSigningDetails;
                        SigningDetails mergedDetails = sharedSigningDetails.mergeLineageWith(
                                signingDetails);
                        if (mergedDetails != sharedSigningDetails) {
                            signatureCheckPs.sharedUser.signatures.mSigningDetails = mergedDetails;
                        }
                        if (signatureCheckPs.sharedUser.signaturesChanged == null) {
                            signatureCheckPs.sharedUser.signaturesChanged = Boolean.FALSE;
                        }
                    }
                } catch (PackageManagerException e) {
                    if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(e);
                    }
                    signingDetails = parsedPackage.getSigningDetails();

                    // If the system app is part of a shared user we allow that shared user to
                    // change
                    // signatures as well as part of an OTA. We still need to verify that the
                    // signatures
                    // are consistent within the shared user for a given boot, so only allow
                    // updating
                    // the signatures on the first package scanned for the shared user (i.e. if the
                    // signaturesChanged state hasn't been initialized yet in SharedUserSetting).
                    if (signatureCheckPs.sharedUser != null) {
                        final Signature[] sharedUserSignatures =
                                signatureCheckPs.sharedUser.signatures.mSigningDetails.signatures;
                        if (signatureCheckPs.sharedUser.signaturesChanged != null
                                && compareSignatures(sharedUserSignatures,
                                parsedPackage.getSigningDetails().signatures)
                                        != PackageManager.SIGNATURE_MATCH) {
                            if (SystemProperties.getInt("ro.product.first_api_level", 0) <= 29) {
                                // Mismatched signatures is an error and silently skipping system
                                // packages will likely break the device in unforeseen ways.
                                // However, we allow the device to boot anyway because, prior to Q,
                                // vendors were not expecting the platform to crash in this
                                // situation.
                                // This WILL be a hard failure on any new API levels after Q.
                                throw new ReconcileFailure(
                                        INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                        "Signature mismatch for shared user: "
                                                + scanResult.pkgSetting.sharedUser);
                            } else {
                                // Treat mismatched signatures on system packages using a shared
                                // UID as
                                // fatal for the system overall, rather than just failing to install
                                // whichever package happened to be scanned later.
                                throw new IllegalStateException(
                                        "Signature mismatch on system package "
                                                + parsedPackage.getPackageName()
                                                + " for shared user "
                                                + scanResult.pkgSetting.sharedUser);
                            }
                        }

                        sharedUserSignaturesChanged = true;
                        signatureCheckPs.sharedUser.signatures.mSigningDetails =
                                parsedPackage.getSigningDetails();
                        signatureCheckPs.sharedUser.signaturesChanged = Boolean.TRUE;
                    }
                    // File a report about this.
                    String msg = "System package " + parsedPackage.getPackageName()
                            + " signature changed; retaining data.";
                    reportSettingsProblem(Log.WARN, msg);
                } catch (IllegalArgumentException e) {
                    // should never happen: certs matched when checking, but not when comparing
                    // old to new for sharedUser
                    throw new RuntimeException(
                            "Signing certificates comparison made on incomparable signing details"
                                    + " but somehow passed verifySignatures!", e);
                }
            }

            result.put(installPackageName,
                    new ReconciledPackage(request, installArgs, scanResult.pkgSetting,
                            res, request.preparedPackages.get(installPackageName), scanResult,
                            deletePackageAction, allowedSharedLibInfos, signingDetails,
                            sharedUserSignaturesChanged, removeAppKeySetData));
        }

        for (String installPackageName : scannedPackages.keySet()) {
            // Check all shared libraries and map to their actual file path.
            // We only do this here for apps not on a system dir, because those
            // are the only ones that can fail an install due to this.  We
            // will take care of the system apps by updating all of their
            // library paths after the scan is done. Also during the initial
            // scan don't update any libs as we do this wholesale after all
            // apps are scanned to avoid dependency based scanning.
            final ScanResult scanResult = scannedPackages.get(installPackageName);
            if ((scanResult.request.scanFlags & SCAN_BOOTING) != 0
                    || (scanResult.request.parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
                continue;
            }
            try {
                result.get(installPackageName).collectedSharedLibraryInfos =
                        collectSharedLibraryInfos(scanResult.request.parsedPackage,
                                combinedPackages, request.sharedLibrarySource,
                                incomingSharedLibraries);

            } catch (PackageManagerException e) {
                throw new ReconcileFailure(e.error, e.getMessage());
            }
        }

        return result;
    }
    
    private void commitPackagesLocked(final CommitRequest request) {
        // TODO: remove any expected failures from this method; this should only be able to fail due
        //       to unavoidable errors (I/O, etc.)
        for (ReconciledPackage reconciledPkg : request.reconciledPackages.values()) {
            final ScanResult scanResult = reconciledPkg.scanResult;
            final ScanRequest scanRequest = scanResult.request;
            final ParsedPackage parsedPackage = scanRequest.parsedPackage;
            final String packageName = parsedPackage.getPackageName();
            final PackageInstalledInfo res = reconciledPkg.installResult;

            if (reconciledPkg.prepareResult.replace) {
                AndroidPackage oldPackage = mPackages.get(packageName);

                // Set the update and install times
                PackageSetting deletedPkgSetting = getPackageSetting(oldPackage.getPackageName());
                reconciledPkg.pkgSetting.firstInstallTime = deletedPkgSetting.firstInstallTime;
                reconciledPkg.pkgSetting.lastUpdateTime = System.currentTimeMillis();

                res.removedInfo.broadcastWhitelist = mAppsFilter.getVisibilityWhitelist(
                                reconciledPkg.pkgSetting, request.mAllUsers, mSettings.mPackages);
                if (reconciledPkg.prepareResult.system) {
                    // Remove existing system package
                    removePackageLI(oldPackage, true);
                    if (!disableSystemPackageLPw(oldPackage)) {
                        // We didn't need to disable the .apk as a current system package,
                        // which means we are replacing another update that is already
                        // installed.  We need to make sure to delete the older one's .apk.
                        res.removedInfo.args = createInstallArgsForExisting(
                                oldPackage.getCodePath(),
                                oldPackage.getCodePath(),
                                getAppDexInstructionSets(
                                        AndroidPackageUtils.getPrimaryCpuAbi(oldPackage,
                                                deletedPkgSetting),
                                        AndroidPackageUtils.getSecondaryCpuAbi(oldPackage,
                                                deletedPkgSetting)));
                    } else {
                        res.removedInfo.args = null;
                    }
                } else {
                    try {
                        // Settings will be written during the call to updateSettingsLI().
                        executeDeletePackageLIF(reconciledPkg.deletePackageAction, packageName,
                                true, request.mAllUsers, false, parsedPackage);
                    } catch (SystemDeleteException e) {
                        if (Build.IS_ENG) {
                            throw new RuntimeException("Unexpected failure", e);
                            // ignore; not possible for non-system app
                        }
                    }
                    // Successfully deleted the old package; proceed with replace.

                    // If deleted package lived in a container, give users a chance to
                    // relinquish resources before killing.
                    if (oldPackage.isExternalStorage()) {
                        if (DEBUG_INSTALL) {
                            Slog.i(TAG, "upgrading pkg " + oldPackage
                                    + " is ASEC-hosted -> UNAVAILABLE");
                        }
                        final int[] uidArray = new int[]{oldPackage.getUid()};
                        final ArrayList<String> pkgList = new ArrayList<>(1);
                        pkgList.add(oldPackage.getPackageName());
                        sendResourcesChangedBroadcast(false, true, pkgList, uidArray, null);
                    }

                    // Update the in-memory copy of the previous code paths.
                    PackageSetting ps1 = mSettings.mPackages.get(
                            reconciledPkg.prepareResult.existingPackage.getPackageName());
                    if ((reconciledPkg.installArgs.installFlags & PackageManager.DONT_KILL_APP)
                            == 0) {
                        if (ps1.mOldCodePaths == null) {
                            ps1.mOldCodePaths = new ArraySet<>();
                        }
                        Collections.addAll(ps1.mOldCodePaths, oldPackage.getBaseCodePath());
                        if (oldPackage.getSplitCodePaths() != null) {
                            Collections.addAll(ps1.mOldCodePaths, oldPackage.getSplitCodePaths());
                        }
                    } else {
                        ps1.mOldCodePaths = null;
                    }

                    if (reconciledPkg.installResult.returnCode
                            == PackageManager.INSTALL_SUCCEEDED) {
                        PackageSetting ps2 = mSettings.getPackageLPr(
                                parsedPackage.getPackageName());
                        if (ps2 != null) {
                            res.removedInfo.removedForAllUsers = mPackages.get(ps2.name) == null;
                        }
                    }
                }
            }

            AndroidPackage pkg = commitReconciledScanResultLocked(reconciledPkg, request.mAllUsers);
            updateSettingsLI(pkg, reconciledPkg.installArgs, request.mAllUsers, res);

            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps != null) {
                res.newUsers = ps.queryInstalledUsers(mUserManager.getUserIds(), true);
                ps.setUpdateAvailable(false /*updateAvailable*/);
            }
            if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                updateSequenceNumberLP(ps, res.newUsers);
                updateInstantAppInstallerLocked(packageName);
            }
        }
    }

    private void updateSettingsLI(AndroidPackage newPackage, InstallArgs installArgs,
            int[] allUsers, PackageInstalledInfo res) {
        updateSettingsInternalLI(newPackage, installArgs, allUsers, res);
    }

    private void updateSettingsInternalLI(AndroidPackage pkg, InstallArgs installArgs,
            int[] allUsers, PackageInstalledInfo res) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        final String pkgName = pkg.getPackageName();
        final int[] installedForUsers = res.origUsers;
        final int installReason = installArgs.installReason;
        InstallSource installSource = installArgs.installSource;
        final String installerPackageName = installSource.installerPackageName;

        if (DEBUG_INSTALL) Slog.d(TAG, "New package installed in " + pkg.getCodePath());
        synchronized (mLock) {
// NOTE: This changes slightly to include UPDATE_PERMISSIONS_ALL regardless of the size of pkg.permissions
            mPermissionManager.updatePermissions(pkgName, pkg);
            // For system-bundled packages, we assume that installing an upgraded version
            // of the package implies that the user actually wants to run that new code,
            // so we enable the package.
            final PackageSetting ps = mSettings.mPackages.get(pkgName);
            final int userId = installArgs.user.getIdentifier();
            if (ps != null) {
                if (pkg.isSystem()) {
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                    }
                    // Enable system package for requested users
                    if (res.origUsers != null) {
                        for (int origUserId : res.origUsers) {
                            if (userId == UserHandle.USER_ALL || userId == origUserId) {
                                ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT,
                                        origUserId, installerPackageName);
                            }
                        }
                    }
                    // Also convey the prior install/uninstall state
                    if (allUsers != null && installedForUsers != null) {
                        for (int currentUserId : allUsers) {
                            final boolean installed = ArrayUtils.contains(
                                    installedForUsers, currentUserId);
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "    user " + currentUserId + " => " + installed);
                            }
                            ps.setInstalled(installed, currentUserId);
                        }
                        // these install state changes will be persisted in the
                        // upcoming call to mSettings.writeLPr().
                    }

                    if (allUsers != null) {
                        for (int currentUserId : allUsers) {
                            ps.resetOverrideComponentLabelIcon(currentUserId);
                        }
                    }
                }

                // Retrieve the overlays for shared libraries of the package.
                if (!ps.getPkgState().getUsesLibraryInfos().isEmpty()) {
                    for (SharedLibraryInfo sharedLib : ps.getPkgState().getUsesLibraryInfos()) {
                        for (int currentUserId : UserManagerService.getInstance().getUserIds()) {
                            if (!sharedLib.isDynamic()) {
                                // TODO(146804378): Support overlaying static shared libraries
                                continue;
                            }
                            final PackageSetting libPs = mSettings.mPackages.get(
                                    sharedLib.getPackageName());
                            if (libPs == null) {
                                continue;
                            }
                            final String[] overlayPaths = libPs.getOverlayPaths(currentUserId);
                            if (overlayPaths != null) {
                                ps.setOverlayPathsForLibrary(sharedLib.getName(),
                                        Arrays.asList(overlayPaths), currentUserId);
                            }
                        }
                    }
                }

                // It's implied that when a user requests installation, they want the app to be
                // installed and enabled. (This does not apply to USER_ALL, which here means only
                // install on users for which the app is already installed).
                if (userId != UserHandle.USER_ALL) {
                    ps.setInstalled(true, userId);
                    ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, userId, installerPackageName);
                }

                mSettings.addInstallerPackageNames(ps.installSource);

                // When replacing an existing package, preserve the original install reason for all
                // users that had the package installed before. Similarly for uninstall reasons.
                final Set<Integer> previousUserIds = new ArraySet<>();
                if (res.removedInfo != null && res.removedInfo.installReasons != null) {
                    final int installReasonCount = res.removedInfo.installReasons.size();
                    for (int i = 0; i < installReasonCount; i++) {
                        final int previousUserId = res.removedInfo.installReasons.keyAt(i);
                        final int previousInstallReason = res.removedInfo.installReasons.valueAt(i);
                        ps.setInstallReason(previousInstallReason, previousUserId);
                        previousUserIds.add(previousUserId);
                    }
                }
                if (res.removedInfo != null && res.removedInfo.uninstallReasons != null) {
                    for (int i = 0; i < res.removedInfo.uninstallReasons.size(); i++) {
                        final int previousUserId = res.removedInfo.uninstallReasons.keyAt(i);
                        final int previousReason = res.removedInfo.uninstallReasons.valueAt(i);
                        ps.setUninstallReason(previousReason, previousUserId);
                    }
                }

                // Set install reason for users that are having the package newly installed.
                final int[] allUsersList = mUserManager.getUserIds();
                if (userId == UserHandle.USER_ALL) {
                    // TODO(b/152629990): It appears that the package doesn't actually get newly
                    //  installed in this case, so the installReason shouldn't get modified?
                    for (int currentUserId : allUsersList) {
                        if (!previousUserIds.contains(currentUserId)) {
                            ps.setInstallReason(installReason, currentUserId);
                        }
                    }
                } else if (!previousUserIds.contains(userId)) {
                    ps.setInstallReason(installReason, userId);
                }
                // Ensure that the uninstall reason is UNKNOWN for users with the package installed.
                for (int currentUserId : allUsersList) {
                    if (ps.getInstalled(currentUserId)) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, currentUserId);
                    }
                }
                mSettings.writeKernelMappingLPr(ps);
            }
            res.name = pkgName;
            res.uid = pkg.getUid();
            res.pkg = pkg;
            res.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
            //to update install status
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "writeSettings");
            mSettings.writeLPr();
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }
    
    private void executePostCommitSteps(CommitRequest commitRequest) {
        final ArraySet<IncrementalStorage> incrementalStorages = new ArraySet<>();
        for (ReconciledPackage reconciledPkg : commitRequest.reconciledPackages.values()) {
            final boolean instantApp = ((reconciledPkg.scanResult.request.scanFlags
                            & PackageManagerService.SCAN_AS_INSTANT_APP) != 0);
            final AndroidPackage pkg = reconciledPkg.pkgSetting.pkg;
            final String packageName = pkg.getPackageName();
            final boolean onIncremental = mIncrementalManager != null
                    && isIncrementalPath(pkg.getCodePath());
            if (onIncremental) {
                IncrementalStorage storage = mIncrementalManager.openStorage(pkg.getCodePath());
                if (storage == null) {
                    throw new IllegalArgumentException(
                            "Install: null storage for incremental package " + packageName);
                }
                incrementalStorages.add(storage);
            }
            prepareAppDataAfterInstallLIF(pkg);
            if (reconciledPkg.prepareResult.clearCodeCache) {
                clearAppDataLIF(pkg, UserHandle.USER_ALL, FLAG_STORAGE_DE | FLAG_STORAGE_CE
                        | FLAG_STORAGE_EXTERNAL | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
            }
            if (reconciledPkg.prepareResult.replace) {
                mDexManager.notifyPackageUpdated(pkg.getPackageName(),
                        pkg.getBaseCodePath(), pkg.getSplitCodePaths());
            }

            // Prepare the application profiles for the new code paths.
            // This needs to be done before invoking dexopt so that any install-time profile
            // can be used for optimizations.
            mArtManagerService.prepareAppProfiles(
                    pkg,
                    resolveUserIds(reconciledPkg.installArgs.user.getIdentifier()),
                    /* updateReferenceProfileContent= */ true);

            // Check whether we need to dexopt the app.
            //
            // NOTE: it is IMPORTANT to call dexopt:
            //   - after doRename which will sync the package data from AndroidPackage and
            //     its corresponding ApplicationInfo.
            //   - after installNewPackageLIF or replacePackageLIF which will update result with the
            //     uid of the application (pkg.applicationInfo.uid).
            //     This update happens in place!
            //
            // We only need to dexopt if the package meets ALL of the following conditions:
            //   1) it is not an instant app or if it is then dexopt is enabled via gservices.
            //   2) it is not debuggable.
            //   3) it is not on Incremental File System.
            //
            // Note that we do not dexopt instant apps by default. dexopt can take some time to
            // complete, so we skip this step during installation. Instead, we'll take extra time
            // the first time the instant app starts. It's preferred to do it this way to provide
            // continuous progress to the useur instead of mysteriously blocking somewhere in the
            // middle of running an instant app. The default behaviour can be overridden
            // via gservices.
            final boolean performDexopt =
                    (!instantApp || Global.getInt(mContext.getContentResolver(),
                    Global.INSTANT_APP_DEXOPT_ENABLED, 0) != 0)
                    && !pkg.isDebuggable()
                    && (!onIncremental);

            if (performDexopt) {
                // Compile the layout resources.
                if (SystemProperties.getBoolean(PRECOMPILE_LAYOUTS, false)) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "compileLayouts");
                    mViewCompiler.compileLayouts(pkg);
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }

                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");
                // Do not run PackageDexOptimizer through the local performDexOpt
                // method because `pkg` may not be in `mPackages` yet.
                //
                // Also, don't fail application installs if the dexopt step fails.
                int flags = DexoptOptions.DEXOPT_BOOT_COMPLETE
                        | DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE;
                if (reconciledPkg.installArgs.installReason == INSTALL_REASON_DEVICE_RESTORE
                        || reconciledPkg.installArgs.installReason == INSTALL_REASON_DEVICE_SETUP) {
                    flags |= DexoptOptions.DEXOPT_FOR_RESTORE;
                }
                DexoptOptions dexoptOptions = new DexoptOptions(packageName,
                        REASON_INSTALL,
                        flags);
                ScanResult result = reconciledPkg.scanResult;

                // This mirrors logic from commitReconciledScanResultLocked, where the library files
                // needed for dexopt are assigned.
                // TODO: Fix this to have 1 mutable PackageSetting for scan/install. If the previous
                //  setting needs to be passed to have a comparison, hide it behind an immutable
                //  interface. There's no good reason to have 3 different ways to access the real
                //  PackageSetting object, only one of which is actually correct.
                PackageSetting realPkgSetting = result.existingSettingCopied
                        ? result.request.pkgSetting : result.pkgSetting;
                if (realPkgSetting == null) {
                    realPkgSetting = reconciledPkg.pkgSetting;
                }

                // Unfortunately, the updated system app flag is only tracked on this PackageSetting
                boolean isUpdatedSystemApp = reconciledPkg.pkgSetting.getPkgState()
                        .isUpdatedSystemApp();

                realPkgSetting.getPkgState().setUpdatedSystemApp(isUpdatedSystemApp);

                mPackageDexOptimizer.performDexOpt(pkg, realPkgSetting,
                        null /* instructionSets */,
                        getOrCreateCompilerPackageStats(pkg),
                        mDexManager.getPackageUseInfoOrDefault(packageName),
                        dexoptOptions);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            // Notify BackgroundDexOptService that the package has been changed.
            // If this is an update of a package which used to fail to compile,
            // BackgroundDexOptService will remove it from its blacklist.
            // TODO: Layering violation
            BackgroundDexOptService.notifyPackageChanged(packageName);

            notifyPackageChangeObserversOnUpdate(reconciledPkg);
        }
        NativeLibraryHelper.waitForNativeBinariesExtraction(incrementalStorages);
    }
