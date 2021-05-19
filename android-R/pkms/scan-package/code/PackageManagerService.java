    private void scanDirLI(File scanDir, int parseFlags, int scanFlags, long currentTime,
            PackageParser2 packageParser, ExecutorService executorService) {
        final File[] files = scanDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            Log.d(TAG, "No files in app dir " + scanDir);
            return;
        }

        if (DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "Scanning app dir " + scanDir + " scanFlags=" + scanFlags
                    + " flags=0x" + Integer.toHexString(parseFlags));
        }

        ParallelPackageParser parallelPackageParser =
                new ParallelPackageParser(packageParser, executorService);

        // Submit files for parsing in parallel
        int fileCount = 0;
        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }
            parallelPackageParser.submit(file, parseFlags);
            fileCount++;
        }

        // Process results one by one
        for (; fileCount > 0; fileCount--) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            Throwable throwable = parseResult.throwable;
            int errorCode = PackageManager.INSTALL_SUCCEEDED;

            if (throwable == null) {
                // TODO(toddke): move lower in the scan chain
                // Static shared libraries have synthetic package names
                if (parseResult.parsedPackage.isStaticSharedLibrary()) {
                    renameStaticSharedLibraryPackage(parseResult.parsedPackage);
                }
                try {
                    addForInitLI(parseResult.parsedPackage, parseFlags, scanFlags,
                            currentTime, null);
                } catch (PackageManagerException e) {
                    errorCode = e.error;
                    Slog.w(TAG, "Failed to scan " + parseResult.scanFile + ": " + e.getMessage());
                }
            } else if (throwable instanceof PackageParserException) {
                PackageParserException e = (PackageParserException)
                        throwable;
                errorCode = e.error;
                Slog.w(TAG, "Failed to parse " + parseResult.scanFile + ": " + e.getMessage());
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + parseResult.scanFile, throwable);
            }

            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0 && errorCode != INSTALL_SUCCEEDED) {
                mApexManager.reportErrorWithApkInApex(scanDir.getAbsolutePath());
            }

            // Delete invalid userdata apps
            if ((scanFlags & SCAN_AS_SYSTEM) == 0
                    && errorCode != PackageManager.INSTALL_SUCCEEDED) {
                logCriticalInfo(Log.WARN,
                        "Deleting invalid package at " + parseResult.scanFile);
                removeCodePathLI(parseResult.scanFile);
            }
        }
    }
    /**
     * Adds a new package to the internal data structures during platform initialization.
     * <p>After adding, the package is known to the system and available for querying.
     * <p>For packages located on the device ROM [eg. packages located in /system, /vendor,
     * etc...], additional checks are performed. Basic verification [such as ensuring
     * matching signatures, checking version codes, etc...] occurs if the package is
     * identical to a previously known package. If the package fails a signature check,
     * the version installed on /data will be removed. If the version of the new package
     * is less than or equal than the version on /data, it will be ignored.
     * <p>Regardless of the package location, the results are applied to the internal
     * structures and the package is made available to the rest of the system.
     * <p>NOTE: The return value should be removed. It's the passed in package object.
     */
    @GuardedBy({"mInstallLock", "mLock"})
    private AndroidPackage addForInitLI(ParsedPackage parsedPackage,
            @ParseFlags int parseFlags, @ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user)
                    throws PackageManagerException {
        final boolean scanSystemPartition = (parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0;
        final String renamedPkgName;
        final PackageSetting disabledPkgSetting;
        final boolean isSystemPkgUpdated;
        final boolean pkgAlreadyExists;
        PackageSetting pkgSetting;

        synchronized (mLock) {
            renamedPkgName = mSettings.getRenamedPackageLPr(parsedPackage.getRealPackage());
            final String realPkgName = getRealPackageName(parsedPackage, renamedPkgName);
            if (realPkgName != null) {
                ensurePackageRenamed(parsedPackage, renamedPkgName);
            }
            final PackageSetting originalPkgSetting = getOriginalPackageLocked(parsedPackage,
                    renamedPkgName);
            final PackageSetting installedPkgSetting = mSettings.getPackageLPr(
                    parsedPackage.getPackageName());
            pkgSetting = originalPkgSetting == null ? installedPkgSetting : originalPkgSetting;
            pkgAlreadyExists = pkgSetting != null;
            final String disabledPkgName = pkgAlreadyExists
                    ? pkgSetting.name : parsedPackage.getPackageName();
            if (scanSystemPartition && !pkgAlreadyExists
                    && mSettings.getDisabledSystemPkgLPr(disabledPkgName) != null) {
                // The updated-package data for /system apk remains inconsistently
                // after the package data for /data apk is lost accidentally.
                // To recover it, enable /system apk and install it as non-updated system app.
                Slog.w(TAG, "Inconsistent package setting of updated system app for "
                        + disabledPkgName + ". To recover it, enable the system app"
                        + "and install it as non-updated system app.");
                mSettings.removeDisabledSystemPackageLPw(disabledPkgName);
            }
            disabledPkgSetting = mSettings.getDisabledSystemPkgLPr(disabledPkgName);
            isSystemPkgUpdated = disabledPkgSetting != null;

            if (DEBUG_INSTALL && isSystemPkgUpdated) {
                Slog.d(TAG, "updatedPkg = " + disabledPkgSetting);
            }

            final SharedUserSetting sharedUserSetting = (parsedPackage.getSharedUserId() != null)
                    ? mSettings.getSharedUserLPw(parsedPackage.getSharedUserId(),
                            0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true)
                    : null;
            if (DEBUG_PACKAGE_SCANNING
                    && (parseFlags & PackageParser.PARSE_CHATTY) != 0
                    && sharedUserSetting != null) {
                Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                        + " (uid=" + sharedUserSetting.userId + "):"
                        + " packages=" + sharedUserSetting.packages);
            }

            if (scanSystemPartition) {
                if (isSystemPkgUpdated) {
                    // we're updating the disabled package, so, scan it as the package setting
                    boolean isPlatformPackage = mPlatformPackage != null
                            && Objects.equals(mPlatformPackage.getPackageName(),
                            parsedPackage.getPackageName());
                    final ScanRequest request = new ScanRequest(parsedPackage, sharedUserSetting,
                            null, disabledPkgSetting /* pkgSetting */,
                            null /* disabledPkgSetting */, null /* originalPkgSetting */,
                            null, parseFlags, scanFlags, isPlatformPackage, user, null);
                    applyPolicy(parsedPackage, parseFlags, scanFlags, mPlatformPackage, true);
                    final ScanResult scanResult =
                            scanPackageOnlyLI(request, mInjector, mFactoryTest, -1L);
                    if (scanResult.existingSettingCopied && scanResult.request.pkgSetting != null) {
                        scanResult.request.pkgSetting.updateFrom(scanResult.pkgSetting);
                    }
                }
            }
        }

        final boolean newPkgChangedPaths =
                pkgAlreadyExists && !pkgSetting.codePathString.equals(parsedPackage.getCodePath());
        final boolean newPkgVersionGreater =
                pkgAlreadyExists && parsedPackage.getLongVersionCode() > pkgSetting.versionCode;
        final boolean isSystemPkgBetter = scanSystemPartition && isSystemPkgUpdated
                && newPkgChangedPaths && newPkgVersionGreater;
        if (isSystemPkgBetter) {
            // The version of the application on /system is greater than the version on
            // /data. Switch back to the application on /system.
            // It's safe to assume the application on /system will correctly scan. If not,
            // there won't be a working copy of the application.
            synchronized (mLock) {
                // just remove the loaded entries from package lists
                mPackages.remove(pkgSetting.name);
            }

            logCriticalInfo(Log.WARN,
                    "System package updated;"
                    + " name: " + pkgSetting.name
                    + "; " + pkgSetting.versionCode + " --> " + parsedPackage.getLongVersionCode()
                    + "; " + pkgSetting.codePathString + " --> " + parsedPackage.getCodePath());

            final InstallArgs args = createInstallArgsForExisting(
                    pkgSetting.codePathString,
                    pkgSetting.resourcePathString, getAppDexInstructionSets(
                            pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
            args.cleanUpResourcesLI();
            synchronized (mLock) {
                mSettings.enableSystemPackageLPw(pkgSetting.name);
            }
        }

        if (scanSystemPartition && isSystemPkgUpdated && !isSystemPkgBetter) {
            // The version of the application on the /system partition is less than or
            // equal to the version on the /data partition. Throw an exception and use
            // the application already installed on the /data partition.
            throw new PackageManagerException(Log.WARN, "Package " + parsedPackage.getPackageName()
                    + " at " + parsedPackage.getCodePath() + " ignored: updated version "
                    + pkgSetting.versionCode + " better than this "
                    + parsedPackage.getLongVersionCode());
        }

        // Verify certificates against what was last scanned. Force re-collecting certificate in two
        // special cases:
        // 1) when scanning system, force re-collect only if system is upgrading.
        // 2) when scannning /data, force re-collect only if the app is privileged (updated from
        // preinstall, or treated as privileged, e.g. due to shared user ID).
        final boolean forceCollect = scanSystemPartition ? mIsUpgrade
                : PackageManagerServiceUtils.isApkVerificationForced(pkgSetting);
        if (DEBUG_VERIFY && forceCollect) {
            Slog.d(TAG, "Force collect certificate of " + parsedPackage.getPackageName());
        }

        // Full APK verification can be skipped during certificate collection, only if the file is
        // in verified partition, or can be verified on access (when apk verity is enabled). In both
        // cases, only data in Signing Block is verified instead of the whole file.
        // TODO(b/136132412): skip for Incremental installation
        final boolean skipVerify = scanSystemPartition
                || (forceCollect && canSkipForcedPackageVerification(parsedPackage));
        collectCertificatesLI(pkgSetting, parsedPackage, forceCollect, skipVerify);

        // Reset profile if the application version is changed
        maybeClearProfilesForUpgradesLI(pkgSetting, parsedPackage);

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        // A new application appeared on /system, but, we already have a copy of
        // the application installed on /data.
        if (scanSystemPartition && !isSystemPkgUpdated && pkgAlreadyExists
                && !pkgSetting.isSystem()) {

            if (!parsedPackage.getSigningDetails()
                    .checkCapability(pkgSetting.signatures.mSigningDetails,
                    PackageParser.SigningDetails.CertCapabilities.INSTALLED_DATA)
                            && !pkgSetting.signatures.mSigningDetails.checkCapability(
                                    parsedPackage.getSigningDetails(),
                                    PackageParser.SigningDetails.CertCapabilities.ROLLBACK)) {
                logCriticalInfo(Log.WARN,
                        "System package signature mismatch;"
                        + " name: " + pkgSetting.name);
                try (@SuppressWarnings("unused") PackageFreezer freezer = freezePackage(
                        parsedPackage.getPackageName(),
                        "scanPackageInternalLI")) {
                    deletePackageLIF(parsedPackage.getPackageName(), null, true, null, 0, null,
                            false, null);
                }
                pkgSetting = null;
            } else if (newPkgVersionGreater) {
                // The application on /system is newer than the application on /data.
                // Simply remove the application on /data [keeping application data]
                // and replace it with the version on /system.
                logCriticalInfo(Log.WARN,
                        "System package enabled;"
                                + " name: " + pkgSetting.name
                                + "; " + pkgSetting.versionCode + " --> "
                                + parsedPackage.getLongVersionCode()
                                + "; " + pkgSetting.codePathString + " --> "
                                + parsedPackage.getCodePath());
                InstallArgs args = createInstallArgsForExisting(
                        pkgSetting.codePathString,
                        pkgSetting.resourcePathString, getAppDexInstructionSets(
                                pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
                synchronized (mInstallLock) {
                    args.cleanUpResourcesLI();
                }
            } else {
                // The application on /system is older than the application on /data. Hide
                // the application on /system and the version on /data will be scanned later
                // and re-added like an update.
                shouldHideSystemApp = true;
                logCriticalInfo(Log.INFO,
                        "System package disabled;"
                                + " name: " + pkgSetting.name
                                + "; old: " + pkgSetting.codePathString + " @ "
                                + pkgSetting.versionCode
                                + "; new: " + parsedPackage.getCodePath() + " @ "
                                + parsedPackage.getCodePath());
            }
        }

        final ScanResult scanResult = scanPackageNewLI(parsedPackage, parseFlags, scanFlags
                | SCAN_UPDATE_SIGNATURE, currentTime, user, null);
        if (scanResult.success) {
            synchronized (mLock) {
                boolean appIdCreated = false;
                try {
                    final String pkgName = scanResult.pkgSetting.name;
                    final Map<String, ReconciledPackage> reconcileResult = reconcilePackagesLocked(
                            new ReconcileRequest(
                                    Collections.singletonMap(pkgName, scanResult),
                                    mSharedLibraries,
                                    mPackages,
                                    Collections.singletonMap(
                                            pkgName, getSettingsVersionForPackage(parsedPackage)),
                                    Collections.singletonMap(pkgName,
                                            getSharedLibLatestVersionSetting(scanResult))),
                            mSettings.mKeySetManagerService);
                    appIdCreated = optimisticallyRegisterAppId(scanResult);
                    commitReconciledScanResultLocked(
                            reconcileResult.get(pkgName), mUserManager.getUserIds());
                } catch (PackageManagerException e) {
                    if (appIdCreated) {
                        cleanUpAppIdCreation(scanResult);
                    }
                    throw e;
                }
            }
        }

        if (shouldHideSystemApp) {
            synchronized (mLock) {
                mSettings.disableSystemPackageLPw(parsedPackage.getPackageName(), true);
            }
        }
        return scanResult.pkgSetting.pkg;
    }

    // TODO: scanPackageNewLI() and scanPackageOnly() should be merged. But, first, commiting
    // the results / removing app data needs to be moved up a level to the callers of this
    // method. Also, we need to solve the problem of potentially creating a new shared user
    // setting. That can probably be done later and patch things up after the fact.
    @GuardedBy({"mInstallLock", "mLock"})
    private ScanResult scanPackageNewLI(@NonNull ParsedPackage parsedPackage,
            final @ParseFlags int parseFlags, @ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, String cpuAbiOverride) throws PackageManagerException {

        final String renamedPkgName = mSettings.getRenamedPackageLPr(
                parsedPackage.getRealPackage());
        final String realPkgName = getRealPackageName(parsedPackage, renamedPkgName);
        if (realPkgName != null) {
            ensurePackageRenamed(parsedPackage, renamedPkgName);
        }
        final PackageSetting originalPkgSetting = getOriginalPackageLocked(parsedPackage,
                renamedPkgName);
        final PackageSetting pkgSetting = mSettings.getPackageLPr(parsedPackage.getPackageName());
        final PackageSetting disabledPkgSetting =
                mSettings.getDisabledSystemPkgLPr(parsedPackage.getPackageName());

        if (mTransferredPackages.contains(parsedPackage.getPackageName())) {
            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                    + " was transferred to another, but its .apk remains");
        }

        scanFlags = adjustScanFlags(scanFlags, pkgSetting, disabledPkgSetting, user, parsedPackage);
        synchronized (mLock) {
            boolean isUpdatedSystemApp;
            if (pkgSetting != null) {
                isUpdatedSystemApp = pkgSetting.getPkgState().isUpdatedSystemApp();
            } else {
                isUpdatedSystemApp = disabledPkgSetting != null;
            }
            applyPolicy(parsedPackage, parseFlags, scanFlags, mPlatformPackage, isUpdatedSystemApp);
            assertPackageIsValid(parsedPackage, parseFlags, scanFlags);

            SharedUserSetting sharedUserSetting = null;
            if (parsedPackage.getSharedUserId() != null) {
                // SIDE EFFECTS; may potentially allocate a new shared user
                sharedUserSetting = mSettings.getSharedUserLPw(parsedPackage.getSharedUserId(),
                        0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true /*create*/);
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                                + " (uid=" + sharedUserSetting.userId + "):"
                                + " packages=" + sharedUserSetting.packages);
                }
            }
            String platformPackageName = mPlatformPackage == null
                    ? null : mPlatformPackage.getPackageName();
            final ScanRequest request = new ScanRequest(parsedPackage, sharedUserSetting,
                    pkgSetting == null ? null : pkgSetting.pkg, pkgSetting, disabledPkgSetting,
                    originalPkgSetting, realPkgName, parseFlags, scanFlags,
                    Objects.equals(parsedPackage.getPackageName(), platformPackageName), user,
                    cpuAbiOverride);
            return scanPackageOnlyLI(request, mInjector, mFactoryTest, currentTime);
        }
    }

    /**
     * Just scans the package without any side effects.
     * <p>Not entirely true at the moment. There is still one side effect -- this
     * method potentially modifies a live {@link PackageSetting} object representing
     * the package being scanned. This will be resolved in the future.
     *
     * @param injector injector for acquiring dependencies
     * @param request Information about the package to be scanned
     * @param isUnderFactoryTest Whether or not the device is under factory test
     * @param currentTime The current time, in millis
     * @return The results of the scan
     */
    @GuardedBy("mInstallLock")
    @VisibleForTesting
    @NonNull
    static ScanResult scanPackageOnlyLI(@NonNull ScanRequest request,
            Injector injector,
            boolean isUnderFactoryTest, long currentTime)
            throws PackageManagerException {
        final PackageAbiHelper packageAbiHelper = injector.getAbiHelper();
        final UserManagerInternal userManager = injector.getUserManagerInternal();
        ParsedPackage parsedPackage = request.parsedPackage;
        PackageSetting pkgSetting = request.pkgSetting;
        final PackageSetting disabledPkgSetting = request.disabledPkgSetting;
        final PackageSetting originalPkgSetting = request.originalPkgSetting;
        final @ParseFlags int parseFlags = request.parseFlags;
        final @ScanFlags int scanFlags = request.scanFlags;
        final String realPkgName = request.realPkgName;
        final SharedUserSetting sharedUserSetting = request.sharedUserSetting;
        final UserHandle user = request.user;
        final boolean isPlatformPackage = request.isPlatformPackage;

        List<String> changedAbiCodePath = null;

        if (DEBUG_PACKAGE_SCANNING) {
            if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                Log.d(TAG, "Scanning package " + parsedPackage.getPackageName());
        }

        // Initialize package source and resource directories
        final File destCodeFile = new File(parsedPackage.getCodePath());
        final File destResourceFile = new File(parsedPackage.getCodePath());

        // We keep references to the derived CPU Abis from settings in oder to reuse
        // them in the case where we're not upgrading or booting for the first time.
        String primaryCpuAbiFromSettings = null;
        String secondaryCpuAbiFromSettings = null;
        boolean needToDeriveAbi = (scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0;
        if (!needToDeriveAbi) {
            if (pkgSetting != null) {
                // TODO(b/154610922): if it is not first boot or upgrade, we should directly use
                // API info from existing package setting. However, stub packages currently do not
                // preserve ABI info, thus the special condition check here. Remove the special
                // check after we fix the stub generation.
                if (pkgSetting.pkg != null && pkgSetting.pkg.isStub()) {
                    needToDeriveAbi = true;
                } else {
                    primaryCpuAbiFromSettings = pkgSetting.primaryCpuAbiString;
                    secondaryCpuAbiFromSettings = pkgSetting.secondaryCpuAbiString;
                }
            } else {
                // Re-scanning a system package after uninstalling updates; need to derive ABI
                needToDeriveAbi = true;
            }
        }

        if (pkgSetting != null && pkgSetting.sharedUser != sharedUserSetting) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Package " + parsedPackage.getPackageName() + " shared user changed from "
                            + (pkgSetting.sharedUser != null
                            ? pkgSetting.sharedUser.name : "<nothing>")
                            + " to "
                            + (sharedUserSetting != null ? sharedUserSetting.name : "<nothing>")
                            + "; replacing with new");
            pkgSetting = null;
        }

        String[] usesStaticLibraries = null;
        if (!parsedPackage.getUsesStaticLibraries().isEmpty()) {
            usesStaticLibraries = new String[parsedPackage.getUsesStaticLibraries().size()];
            parsedPackage.getUsesStaticLibraries().toArray(usesStaticLibraries);
        }
        // TODO(b/135203078): Remove appInfoFlag usage in favor of individually assigned booleans
        //  to avoid adding something that's unsupported due to lack of state, since it's called
        //  with null.
        final boolean createNewPackage = (pkgSetting == null);
        if (createNewPackage) {
            final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
            final boolean virtualPreload = (scanFlags & SCAN_AS_VIRTUAL_PRELOAD) != 0;

            // Flags contain system values stored in the server variant of AndroidPackage,
            // and so the server-side PackageInfoUtils is still called, even without a
            // PackageSetting to pass in.
            int pkgFlags = PackageInfoUtils.appInfoFlags(parsedPackage, null);
            int pkgPrivateFlags = PackageInfoUtils.appInfoPrivateFlags(parsedPackage, null);

            // REMOVE SharedUserSetting from method; update in a separate call
            pkgSetting = Settings.createNewSetting(parsedPackage.getPackageName(),
                    originalPkgSetting, disabledPkgSetting, realPkgName, sharedUserSetting,
                    destCodeFile, destResourceFile, parsedPackage.getNativeLibraryRootDir(),
                    AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage),
                    AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage),
                    parsedPackage.getVersionCode(), pkgFlags, pkgPrivateFlags, user,
                    true /*allowInstall*/, instantApp, virtualPreload,
                    UserManagerService.getInstance(), usesStaticLibraries,
                    parsedPackage.getUsesStaticLibrariesVersions(), parsedPackage.getMimeGroups());
        } else {
            // make a deep copy to avoid modifying any existing system state.
            pkgSetting = new PackageSetting(pkgSetting);
            pkgSetting.pkg = parsedPackage;

            // REMOVE SharedUserSetting from method; update in a separate call.
            //
            // TODO(narayan): This update is bogus. nativeLibraryDir & primaryCpuAbi,
            // secondaryCpuAbi are not known at this point so we always update them
            // to null here, only to reset them at a later point.
            Settings.updatePackageSetting(pkgSetting, disabledPkgSetting, sharedUserSetting,
                    destCodeFile, destResourceFile, parsedPackage.getNativeLibraryDir(),
                    AndroidPackageUtils.getPrimaryCpuAbi(parsedPackage, pkgSetting),
                    AndroidPackageUtils.getSecondaryCpuAbi(parsedPackage, pkgSetting),
                    PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting),
                    PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting),
                    UserManagerService.getInstance(),
                    usesStaticLibraries, parsedPackage.getUsesStaticLibrariesVersions(),
                    parsedPackage.getMimeGroups());
        }
        if (createNewPackage && originalPkgSetting != null) {
            // This is the initial transition from the original package, so,
            // fix up the new package's name now. We must do this after looking
            // up the package under its new name, so getPackageLP takes care of
            // fiddling things correctly.
            parsedPackage.setPackageName(originalPkgSetting.name);

            // File a report about this.
            String msg = "New package " + pkgSetting.realName
                    + " renamed to replace old package " + pkgSetting.name;
            reportSettingsProblem(Log.WARN, msg);
        }

        final int userId = (user == null ? UserHandle.USER_SYSTEM : user.getIdentifier());
        // for existing packages, change the install state; but, only if it's explicitly specified
        if (!createNewPackage) {
            final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
            final boolean fullApp = (scanFlags & SCAN_AS_FULL_APP) != 0;
            setInstantAppForUser(injector, pkgSetting, userId, instantApp, fullApp);
        }
        // TODO(patb): see if we can do away with disabled check here.
        if (disabledPkgSetting != null
                || (0 != (scanFlags & SCAN_NEW_INSTALL)
                && pkgSetting != null && pkgSetting.isSystem())) {
            pkgSetting.getPkgState().setUpdatedSystemApp(true);
        }

        parsedPackage
                .setSeInfo(SELinuxMMAC.getSeInfo(parsedPackage, sharedUserSetting,
                        injector.getCompatibility()))
                .setSeInfoUser(SELinuxUtil.assignSeinfoUser(pkgSetting.readUserState(
                        userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM : userId)));

        if (parsedPackage.isSystem()) {
            configurePackageComponents(parsedPackage);
        }

        final String cpuAbiOverride = deriveAbiOverride(request.cpuAbiOverride, pkgSetting);

        if ((scanFlags & SCAN_NEW_INSTALL) == 0) {
            if (needToDeriveAbi) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "derivePackageAbi");
                final boolean extractNativeLibs = !AndroidPackageUtils.isLibrary(parsedPackage);
                final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths> derivedAbi =
                        packageAbiHelper.derivePackageAbi(parsedPackage,
                                pkgSetting.getPkgState().isUpdatedSystemApp(), cpuAbiOverride,
                                extractNativeLibs);
                derivedAbi.first.applyTo(parsedPackage);
                derivedAbi.second.applyTo(parsedPackage);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

                // Some system apps still use directory structure for native libraries
                // in which case we might end up not detecting abi solely based on apk
                // structure. Try to detect abi based on directory structure.

                String pkgRawPrimaryCpuAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage);
                if (parsedPackage.isSystem() && !pkgSetting.getPkgState().isUpdatedSystemApp() &&
                        pkgRawPrimaryCpuAbi == null) {
                    final PackageAbiHelper.Abis abis = packageAbiHelper.getBundledAppAbis(
                            parsedPackage);
                    abis.applyTo(parsedPackage);
                    abis.applyTo(pkgSetting);
                    final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                            packageAbiHelper.getNativeLibraryPaths(parsedPackage, pkgSetting,
                                    sAppLib32InstallDir);
                    nativeLibraryPaths.applyTo(parsedPackage);
                }
            } else {
                // This is not a first boot or an upgrade, don't bother deriving the
                // ABI during the scan. Instead, trust the value that was stored in the
                // package setting.
                parsedPackage.setPrimaryCpuAbi(primaryCpuAbiFromSettings)
                        .setSecondaryCpuAbi(secondaryCpuAbiFromSettings);

                final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                        packageAbiHelper.getNativeLibraryPaths(parsedPackage,
                                pkgSetting, sAppLib32InstallDir);
                nativeLibraryPaths.applyTo(parsedPackage);

                if (DEBUG_ABI_SELECTION) {
                    Slog.i(TAG, "Using ABIS and native lib paths from settings : " +
                            parsedPackage.getPackageName() + " " +
                            AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage)
                            + ", "
                            + AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage));
                }
            }
        } else {
            if ((scanFlags & SCAN_MOVE) != 0) {
                // We haven't run dex-opt for this move (since we've moved the compiled output too)
                // but we already have this packages package info in the PackageSetting. We just
                // use that and derive the native library path based on the new codepath.
                parsedPackage.setPrimaryCpuAbi(pkgSetting.primaryCpuAbiString)
                        .setSecondaryCpuAbi(pkgSetting.secondaryCpuAbiString);
            }

            // Set native library paths again. For moves, the path will be updated based on the
            // ABIs we've determined above. For non-moves, the path will be updated based on the
            // ABIs we determined during compilation, but the path will depend on the final
            // package path (after the rename away from the stage path).
            final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                    packageAbiHelper.getNativeLibraryPaths(parsedPackage, pkgSetting,
                            sAppLib32InstallDir);
            nativeLibraryPaths.applyTo(parsedPackage);
        }

        // This is a special case for the "system" package, where the ABI is
        // dictated by the zygote configuration (and init.rc). We should keep track
        // of this ABI so that we can deal with "normal" applications that run under
        // the same UID correctly.
        if (isPlatformPackage) {
            parsedPackage.setPrimaryCpuAbi(VMRuntime.getRuntime().is64Bit() ?
                    Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0]);
        }

        // If there's a mismatch between the abi-override in the package setting
        // and the abiOverride specified for the install. Warn about this because we
        // would've already compiled the app without taking the package setting into
        // account.
        if ((scanFlags & SCAN_NO_DEX) == 0 && (scanFlags & SCAN_NEW_INSTALL) != 0) {
            if (cpuAbiOverride == null) {
                Slog.w(TAG, "Ignoring persisted ABI override " + cpuAbiOverride +
                        " for package " + parsedPackage.getPackageName());
            }
        }

        pkgSetting.primaryCpuAbiString = AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage);
        pkgSetting.secondaryCpuAbiString = AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage);
        pkgSetting.cpuAbiOverrideString = cpuAbiOverride;

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Resolved nativeLibraryRoot for " + parsedPackage.getPackageName()
                    + " to root=" + parsedPackage.getNativeLibraryRootDir() + ", isa="
                    + parsedPackage.isNativeLibraryRootRequiresIsa());
        }

        // Push the derived path down into PackageSettings so we know what to
        // clean up at uninstall time.
        pkgSetting.legacyNativeLibraryPathString = parsedPackage.getNativeLibraryRootDir();

        if (DEBUG_ABI_SELECTION) {
            Log.d(TAG, "Abis for package[" + parsedPackage.getPackageName() + "] are" +
                    " primary=" + AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage) +
                    " secondary=" + AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage));
        }

        if ((scanFlags & SCAN_BOOTING) == 0 && pkgSetting.sharedUser != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            changedAbiCodePath = applyAdjustedAbiToSharedUser(pkgSetting.sharedUser, parsedPackage,
                    packageAbiHelper.getAdjustedAbiForSharedUser(
                            pkgSetting.sharedUser.packages, parsedPackage));
        }

        parsedPackage.setFactoryTest(isUnderFactoryTest && parsedPackage.getRequestedPermissions()
                .contains(android.Manifest.permission.FACTORY_TEST));

        if (parsedPackage.isSystem()) {
            pkgSetting.setIsOrphaned(true);
        }

        // Take care of first install / last update times.
        final long scanFileTime = getLastModifiedTime(parsedPackage);
        if (currentTime != 0) {
            if (pkgSetting.firstInstallTime == 0) {
                pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = currentTime;
            } else if ((scanFlags & SCAN_UPDATE_TIME) != 0) {
                pkgSetting.lastUpdateTime = currentTime;
            }
        } else if (pkgSetting.firstInstallTime == 0) {
            // We need *something*.  Take time time stamp of the file.
            pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = scanFileTime;
        } else if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
            if (scanFileTime != pkgSetting.timeStamp) {
                // A package on the system image has changed; consider this
                // to be an update.
                pkgSetting.lastUpdateTime = scanFileTime;
            }
        }
        pkgSetting.setTimeStamp(scanFileTime);
        // TODO(b/135203078): Remove, move to constructor
        pkgSetting.pkg = parsedPackage;
        pkgSetting.pkgFlags = PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting);
        pkgSetting.pkgPrivateFlags =
                PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting);
        if (parsedPackage.getLongVersionCode() != pkgSetting.versionCode) {
            pkgSetting.versionCode = parsedPackage.getLongVersionCode();
        }
        // Update volume if needed
        final String volumeUuid = parsedPackage.getVolumeUuid();
        if (!Objects.equals(volumeUuid, pkgSetting.volumeUuid)) {
            Slog.i(PackageManagerService.TAG,
                    "Update" + (pkgSetting.isSystem() ? " system" : "")
                    + " package " + parsedPackage.getPackageName()
                    + " volume from " + pkgSetting.volumeUuid
                    + " to " + volumeUuid);
            pkgSetting.volumeUuid = volumeUuid;
        }

        SharedLibraryInfo staticSharedLibraryInfo = null;
        if (!TextUtils.isEmpty(parsedPackage.getStaticSharedLibName())) {
            staticSharedLibraryInfo =
                    AndroidPackageUtils.createSharedLibraryForStatic(parsedPackage);
        }
        List<SharedLibraryInfo> dynamicSharedLibraryInfos = null;
        if (!ArrayUtils.isEmpty(parsedPackage.getLibraryNames())) {
            dynamicSharedLibraryInfos = new ArrayList<>(parsedPackage.getLibraryNames().size());
            for (String name : parsedPackage.getLibraryNames()) {
                dynamicSharedLibraryInfos.add(
                        AndroidPackageUtils.createSharedLibraryForDynamic(parsedPackage, name));
            }
        }

        return new ScanResult(request, true, pkgSetting, changedAbiCodePath,
                !createNewPackage /* existingSettingCopied */, staticSharedLibraryInfo,
                dynamicSharedLibraryInfos);
    }

    @GuardedBy("mLock")
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

    /**
     * Commits the package scan and modifies system state.
     * <p><em>WARNING:</em> The method may throw an excpetion in the middle
     * of committing the package, leaving the system in an inconsistent state.
     * This needs to be fixed so, once we get to this point, no errors are
     * possible and the system is not left in an inconsistent state.
     */
    @GuardedBy({"mLock", "mInstallLock"})
    private AndroidPackage commitReconciledScanResultLocked(
            @NonNull ReconciledPackage reconciledPkg, int[] allUsers) {
        final ScanResult result = reconciledPkg.scanResult;
        final ScanRequest request = result.request;
        // TODO(b/135203078): Move this even further away
        ParsedPackage parsedPackage = request.parsedPackage;
        if ("android".equals(parsedPackage.getPackageName())) {
            // TODO(b/135203078): Move this to initial parse
            parsedPackage.setVersionCode(mSdkVersion)
                    .setVersionCodeMajor(0);
        }
        final AndroidPackage oldPkg = request.oldPkg;
        final @ParseFlags int parseFlags = request.parseFlags;
        final @ScanFlags int scanFlags = request.scanFlags;
        final PackageSetting oldPkgSetting = request.oldPkgSetting;
        final PackageSetting originalPkgSetting = request.originalPkgSetting;
        final UserHandle user = request.user;
        final String realPkgName = request.realPkgName;
        final List<String> changedAbiCodePath = result.changedAbiCodePath;
        final PackageSetting pkgSetting;
        if (request.pkgSetting != null && request.pkgSetting.sharedUser != null
                && request.pkgSetting.sharedUser != result.pkgSetting.sharedUser) {
            // shared user changed, remove from old shared user
            request.pkgSetting.sharedUser.removePackage(request.pkgSetting);
        }
        if (result.existingSettingCopied) {
            pkgSetting = request.pkgSetting;
            pkgSetting.updateFrom(result.pkgSetting);
        } else {
            pkgSetting = result.pkgSetting;
            if (originalPkgSetting != null) {
                mSettings.addRenamedPackageLPw(parsedPackage.getRealPackage(),
                        originalPkgSetting.name);
                mTransferredPackages.add(originalPkgSetting.name);
            } else {
                mSettings.removeRenamedPackageLPw(parsedPackage.getPackageName());
            }
        }
        if (pkgSetting.sharedUser != null) {
            pkgSetting.sharedUser.addPackage(pkgSetting);
        }
        if (reconciledPkg.installArgs != null && reconciledPkg.installArgs.forceQueryableOverride) {
            pkgSetting.forceQueryableOverride = true;
        }

        // If this is part of a standard install, set the initiating package name, else rely on
        // previous device state.
        if (reconciledPkg.installArgs != null) {
            InstallSource installSource = reconciledPkg.installArgs.installSource;
            if (installSource.initiatingPackageName != null) {
                final PackageSetting ips = mSettings.mPackages.get(
                        installSource.initiatingPackageName);
                if (ips != null) {
                    installSource = installSource.setInitiatingPackageSignatures(
                            ips.signatures);
                }
            }
            pkgSetting.setInstallSource(installSource);
        }

        // TODO(toddke): Consider a method specifically for modifying the Package object
        // post scan; or, moving this stuff out of the Package object since it has nothing
        // to do with the package on disk.
        // We need to have this here because addUserToSettingLPw() is sometimes responsible
        // for creating the application ID. If we did this earlier, we would be saving the
        // correct ID.
        parsedPackage.setUid(pkgSetting.appId);
        final AndroidPackage pkg = parsedPackage.hideAsFinal();

        mSettings.writeUserRestrictionsLPw(pkgSetting, oldPkgSetting);

        if (realPkgName != null) {
            mTransferredPackages.add(pkg.getPackageName());
        }

        if (reconciledPkg.collectedSharedLibraryInfos != null) {
            executeSharedLibrariesUpdateLPr(pkg, pkgSetting, null, null,
                    reconciledPkg.collectedSharedLibraryInfos, allUsers);
        }

        final KeySetManagerService ksms = mSettings.mKeySetManagerService;
        if (reconciledPkg.removeAppKeySetData) {
            ksms.removeAppKeySetDataLPw(pkg.getPackageName());
        }
        if (reconciledPkg.sharedUserSignaturesChanged) {
            pkgSetting.sharedUser.signaturesChanged = Boolean.TRUE;
            pkgSetting.sharedUser.signatures.mSigningDetails = reconciledPkg.signingDetails;
        }
        pkgSetting.signatures.mSigningDetails = reconciledPkg.signingDetails;

        if (!pkg.getAdoptPermissions().isEmpty()) {
            // This package wants to adopt ownership of permissions from
            // another package.
            for (int i = pkg.getAdoptPermissions().size() - 1; i >= 0; i--) {
                final String origName = pkg.getAdoptPermissions().get(i);
                final PackageSetting orig = mSettings.getPackageLPr(origName);
                if (orig != null) {
                    if (verifyPackageUpdateLPr(orig, pkg)) {
                        Slog.i(TAG, "Adopting permissions from " + origName + " to "
                                + pkg.getPackageName());
                        mSettings.mPermissions.transferPermissions(origName, pkg.getPackageName());
                    }
                }
            }
        }

        if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
            for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                final String codePathString = changedAbiCodePath.get(i);
                try {
                    mInstaller.rmdex(codePathString,
                            getDexCodeInstructionSet(getPreferredInstructionSet()));
                } catch (InstallerException ignored) {
                }
            }
        }

        final int userId = user == null ? 0 : user.getIdentifier();
        // Modify state for the given package setting
        commitPackageSettings(pkg, oldPkg, pkgSetting, scanFlags,
                (parseFlags & PackageParser.PARSE_CHATTY) != 0 /*chatty*/, reconciledPkg);
        if (pkgSetting.getInstantApp(userId)) {
            mInstantAppRegistry.addInstantAppLPw(userId, pkgSetting.appId);
        }

        return pkg;
    }

    /**
     * Adds a scanned package to the system. When this method is finished, the package will
     * be available for query, resolution, etc...
     */
    private void commitPackageSettings(AndroidPackage pkg,
            @Nullable AndroidPackage oldPkg, PackageSetting pkgSetting,
            final @ScanFlags int scanFlags, boolean chatty, ReconciledPackage reconciledPkg) {
        final String pkgName = pkg.getPackageName();
        if (mCustomResolverComponentName != null &&
                mCustomResolverComponentName.getPackageName().equals(pkg.getPackageName())) {
            setUpCustomResolverActivity(pkg, pkgSetting);
        }

        if (pkg.getPackageName().equals("android")) {
            synchronized (mLock) {
                // Set up information for our fall-back user intent resolution activity.
                mPlatformPackage = pkg;

                // The instance stored in PackageManagerService is special cased to be non-user
                // specific, so initialize all the needed fields here.
                mAndroidApplication = pkg.toAppInfoWithoutState();
                mAndroidApplication.flags = PackageInfoUtils.appInfoFlags(pkg, pkgSetting);
                mAndroidApplication.privateFlags =
                        PackageInfoUtils.appInfoPrivateFlags(pkg, pkgSetting);
                mAndroidApplication.initForUser(UserHandle.USER_SYSTEM);

                if (!mResolverReplaced) {
                    mResolveActivity.applicationInfo = mAndroidApplication;
                    mResolveActivity.name = ResolverActivity.class.getName();
                    mResolveActivity.packageName = mAndroidApplication.packageName;
                    mResolveActivity.processName = "system:ui";
                    mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                    mResolveActivity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                    mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
                    mResolveActivity.theme = R.style.Theme_Material_Dialog_Alert;
                    mResolveActivity.exported = true;
                    mResolveActivity.enabled = true;
                    mResolveActivity.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
                    mResolveActivity.configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
                            | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                            | ActivityInfo.CONFIG_SCREEN_LAYOUT
                            | ActivityInfo.CONFIG_ORIENTATION
                            | ActivityInfo.CONFIG_KEYBOARD
                            | ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
                    mResolveInfo.activityInfo = mResolveActivity;
                    mResolveInfo.priority = 0;
                    mResolveInfo.preferredOrder = 0;
                    mResolveInfo.match = 0;
                    mResolveComponentName = new ComponentName(
                            mAndroidApplication.packageName, mResolveActivity.name);
                }
            }
        }

        ArrayList<AndroidPackage> clientLibPkgs = null;
        // writer
        synchronized (mLock) {
            if (!ArrayUtils.isEmpty(reconciledPkg.allowedSharedLibraryInfos)) {
                for (SharedLibraryInfo info : reconciledPkg.allowedSharedLibraryInfos) {
                    commitSharedLibraryInfoLocked(info);
                }
                final Map<String, AndroidPackage> combinedSigningDetails =
                        reconciledPkg.getCombinedAvailablePackages();
                try {
                    // Shared libraries for the package need to be updated.
                    updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                            combinedSigningDetails);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "updateSharedLibrariesLPr failed: ", e);
                }
                // Update all applications that use this library. Skip when booting
                // since this will be done after all packages are scaned.
                if ((scanFlags & SCAN_BOOTING) == 0) {
                    clientLibPkgs = updateAllSharedLibrariesLocked(pkg, pkgSetting,
                            combinedSigningDetails);
                }
            }
        }
        if (reconciledPkg.installResult != null) {
            reconciledPkg.installResult.libraryConsumers = clientLibPkgs;
        }

        if ((scanFlags & SCAN_BOOTING) != 0) {
            // No apps can run during boot scan, so they don't need to be frozen
        } else if ((scanFlags & SCAN_DONT_KILL_APP) != 0) {
            // Caller asked to not kill app, so it's probably not frozen
        } else if ((scanFlags & SCAN_IGNORE_FROZEN) != 0) {
            // Caller asked us to ignore frozen check for some reason; they
            // probably didn't know the package name
        } else {
            // We're doing major surgery on this package, so it better be frozen
            // right now to keep it from launching
            checkPackageFrozen(pkgName);
        }

        // Also need to kill any apps that are dependent on the library.
        if (clientLibPkgs != null) {
            for (int i=0; i<clientLibPkgs.size(); i++) {
                AndroidPackage clientPkg = clientLibPkgs.get(i);
                killApplication(clientPkg.getPackageName(),
                        clientPkg.getUid(), "update lib");
            }
        }

        // writer
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        synchronized (mLock) {
            // We don't expect installation to fail beyond this point

            // Add the new setting to mSettings
            mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPackages.put(pkg.getPackageName(), pkg);
            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
                mApexManager.registerApkInApex(pkg);
            }

            // Add the package's KeySets to the global KeySetManagerService
            KeySetManagerService ksms = mSettings.mKeySetManagerService;
            ksms.addScannedPackageLPw(pkg);

            mComponentResolver.addAllComponents(pkg, chatty);
            final boolean isReplace =
                    reconciledPkg.prepareResult != null && reconciledPkg.prepareResult.replace;
            mAppsFilter.addPackage(pkgSetting, isReplace);

            // Don't allow ephemeral applications to define new permissions groups.
            if ((scanFlags & SCAN_AS_INSTANT_APP) != 0) {
                Slog.w(TAG, "Permission groups from package " + pkg.getPackageName()
                        + " ignored: instant apps cannot define new permission groups.");
            } else {
                mPermissionManager.addAllPermissionGroups(pkg, chatty);
            }

            // If a permission has had its defining app changed, or it has had its protection
            // upgraded, we need to revoke apps that hold it
            final List<String> permissionsWithChangedDefinition;
            // Don't allow ephemeral applications to define new permissions.
            if ((scanFlags & SCAN_AS_INSTANT_APP) != 0) {
                permissionsWithChangedDefinition = null;
                Slog.w(TAG, "Permissions from package " + pkg.getPackageName()
                        + " ignored: instant apps cannot define new permissions.");
            } else {
                permissionsWithChangedDefinition =
                        mPermissionManager.addAllPermissions(pkg, chatty);
            }

            int collectionSize = ArrayUtils.size(pkg.getInstrumentations());
            StringBuilder r = null;
            int i;
            for (i = 0; i < collectionSize; i++) {
                ParsedInstrumentation a = pkg.getInstrumentations().get(i);
                a.setPackageName(pkg.getPackageName());
                mInstrumentation.put(a.getComponentName(), a);
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.getName());
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            if (!pkg.getProtectedBroadcasts().isEmpty()) {
                synchronized (mProtectedBroadcasts) {
                    mProtectedBroadcasts.addAll(pkg.getProtectedBroadcasts());
                }
            }

            boolean hasOldPkg = oldPkg != null;
            boolean hasPermissionDefinitionChanges =
                    !CollectionUtils.isEmpty(permissionsWithChangedDefinition);
            if (hasOldPkg || hasPermissionDefinitionChanges) {
                // We need to call revokeRuntimePermissionsIfGroupChanged async as permission
                // revoke callbacks from this method might need to kill apps which need the
                // mPackages lock on a different thread. This would dead lock.
                //
                // Hence create a copy of all package names and pass it into
                // revokeRuntimePermissionsIfGroupChanged. Only for those permissions might get
                // revoked. If a new package is added before the async code runs the permission
                // won't be granted yet, hence new packages are no problem.
                final ArrayList<String> allPackageNames = new ArrayList<>(mPackages.keySet());

                AsyncTask.execute(() -> {
                    if (hasOldPkg) {
                        mPermissionManager.revokeRuntimePermissionsIfGroupChanged(pkg, oldPkg,
                                allPackageNames);
                    }
                    if (hasPermissionDefinitionChanges) {
                        mPermissionManager.revokeRuntimePermissionsIfPermissionDefinitionChanged(
                                permissionsWithChangedDefinition, allPackageNames);
                    }
                });
            }
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

