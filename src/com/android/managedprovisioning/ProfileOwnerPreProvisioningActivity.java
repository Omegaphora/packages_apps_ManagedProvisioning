/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning;

import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME;
import static com.android.managedprovisioning.EncryptDeviceActivity.EXTRA_RESUME;
import static com.android.managedprovisioning.EncryptDeviceActivity.EXTRA_RESUME_TARGET;
import static com.android.managedprovisioning.EncryptDeviceActivity.TARGET_PROFILE_OWNER;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;

import java.util.List;

/**
 * The activity sets up the environment in which the {@link ProfileOwnerProvisioningActivity} can be run.
 * It makes sure the device is encrypted, the current launcher supports managed profiles, the
 * provisioning intent extras are valid, and that the already present managed profile is removed.
 */
public class ProfileOwnerPreProvisioningActivity extends Activity
        implements UserConsentDialog.ConsentCallback {

    private static final String MANAGE_USERS_PERMISSION = "android.permission.MANAGE_USERS";

    // Note: must match the constant defined in HomeSettings
    private static final String EXTRA_SUPPORT_MANAGED_PROFILES = "support_managed_profiles";

    // Aliases to start profile owner provisioning with and without MANAGE_USERS permission
    protected static final ComponentName ALIAS_CHECK_CALLER =
            new ComponentName("com.android.managedprovisioning",
                    "com.android.managedprovisioning.ProfileOwnerProvisioningActivity");

    protected static final ComponentName ALIAS_NO_CHECK_CALLER =
            new ComponentName("com.android.managedprovisioning",
                    "com.android.managedprovisioning.ProfileOwnerProvisioningActivityNoCallerCheck");

    protected static final int PROVISIONING_REQUEST_CODE = 3;
    protected static final int ENCRYPT_DEVICE_REQUEST_CODE = 2;
    protected static final int CHANGE_LAUNCHER_REQUEST_CODE = 1;

    private String mMdmPackageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LayoutInflater inflater = getLayoutInflater();
        View contentView = inflater.inflate(R.layout.user_consent, null);
        setContentView(contentView);

        // Check whether system has the required managed profile feature.
        if (!systemHasManagedProfileFeature()) {
            showErrorAndClose(R.string.managed_provisioning_not_supported,
                    "Exiting managed profile provisioning, "
                    + "managed profiles feature is not available");
            return;
        }
        if (Process.myUserHandle().getIdentifier() != UserHandle.USER_OWNER) {
            showErrorAndClose(R.string.user_is_not_owner,
                    "Exiting managed profile provisioning, calling user is not owner.");
            return;
        }

        // Initialize member variables from the intent, stop if the intent wasn't valid.
        try {
            initialize(getIntent());
        } catch (ProvisioningFailedException e) {
            showErrorAndClose(R.string.managed_provisioning_error_text, e.getMessage());
            return;
        }

        setMdmIcon(mMdmPackageName);

        // If the caller started us via ALIAS_NO_CHECK_CALLER then they must have permission to
        // MANAGE_USERS since it is a restricted intent. Otherwise, check the calling package.
        boolean hasManageUsersPermission = (getComponentName().equals(ALIAS_NO_CHECK_CALLER));
        if (!hasManageUsersPermission) {
            // Calling package has to equal the requested device admin package or has to be system.
            String callingPackage = getCallingPackage();
            if (callingPackage == null) {
                showErrorAndClose(R.string.managed_provisioning_error_text,
                        "Calling package is null. " +
                        "Was startActivityForResult used to start this activity?");
                return;
            }
            if (!callingPackage.equals(mMdmPackageName)
                    && !packageHasManageUsersPermission(callingPackage)) {
                showErrorAndClose(R.string.managed_provisioning_error_text, "Permission denied, "
                        + "calling package tried to set a different package as profile owner. "
                        + "The system MANAGE_USERS permission is required.");
                return;
            }
        }

        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        String deviceOwner = dpm.getDeviceOwner();
        if (deviceOwner != null && !deviceOwner.equals(mMdmPackageName)) {
            showErrorAndClose(R.string.managed_provisioning_error_text, "Permission denied, "
                    + "profile owner must be in the same package as device owner.");
            return;
        }

        // If there is already a managed profile, allow the user to cancel or delete it.
        int existingManagedProfileUserId = alreadyHasManagedProfile();
        if (existingManagedProfileUserId != -1) {
            showManagedProfileExistsDialog(existingManagedProfileUserId);
        } else {
            showStartProvisioningScreen();
        }
    }

    private void showStartProvisioningScreen() {
        Button positiveButton = (Button) findViewById(R.id.positive_button);
        positiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkEncryptedAndStartProvisioningService();
            }
        });
    }

    private boolean packageHasManageUsersPermission(String pkg) {
        return PackageManager.PERMISSION_GRANTED == getPackageManager()
                .checkPermission(MANAGE_USERS_PERMISSION, pkg);
    }

    private boolean systemHasManagedProfileFeature() {
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS);
    }

    private boolean currentLauncherSupportsManagedProfiles() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);

        PackageManager pm = getPackageManager();
        ResolveInfo launcherResolveInfo
                = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (launcherResolveInfo == null) {
            return false;
        }
        try {
            ApplicationInfo launcherAppInfo = getPackageManager().getApplicationInfo(
                    launcherResolveInfo.activityInfo.packageName, 0 /* default flags */);
            return versionNumberAtLeastL(launcherAppInfo.targetSdkVersion);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean versionNumberAtLeastL(int versionNumber) {
        return versionNumber >= Build.VERSION_CODES.LOLLIPOP;
    }

    private void setMdmIcon(String packageName) {
        if (packageName != null) {
            PackageManager pm = getPackageManager();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, /* default flags */ 0);
                if (ai != null) {
                    Drawable packageIcon = pm.getApplicationIcon(packageName);
                    ImageView imageView = (ImageView) findViewById(R.id.mdm_icon_view);
                    imageView.setImageDrawable(packageIcon);

                    String appLabel = pm.getApplicationLabel(ai).toString();
                    TextView deviceManagerName = (TextView) findViewById(R.id.device_manager_name);
                    deviceManagerName.setText(appLabel);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Package does not exist, ignore. Should never happen.
                ProvisionLogger.loge("Package does not exist. Should never happen.");
            }
        }
    }

    /**
     * Checks if all required provisioning parameters are provided.
     * Does not check for extras that are optional such as wifi ssid.
     * Also checks whether type of admin extras bundle (if present) is PersistableBundle.
     *
     * @param intent The intent that started provisioning
     */
    private void initialize(Intent intent) throws ProvisioningFailedException {
        // Check if the admin extras bundle is of the right type.
        try {
            PersistableBundle bundle = (PersistableBundle) getIntent().getParcelableExtra(
                    EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        } catch (ClassCastException e) {
            throw new ProvisioningFailedException("Extra "
                    + EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
                    + " must be of type PersistableBundle.", e);
        }

        // Validate package name and check if the package is installed
        mMdmPackageName = intent.getStringExtra(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        if (TextUtils.isEmpty(mMdmPackageName)) {
            throw new ProvisioningFailedException("Missing intent extra: "
                    + EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME);
        } else {
            try {
                this.getPackageManager().getPackageInfo(mMdmPackageName, 0);
            } catch (NameNotFoundException e) {
                throw new ProvisioningFailedException("Mdm "+ mMdmPackageName
                        + " is not installed. ", e);
            }
        }
    }

    /**
     * If the device is encrypted start the service which does the provisioning, otherwise ask for
     * user consent to encrypt the device.
     */
    private void checkEncryptedAndStartProvisioningService() {
        if (EncryptDeviceActivity.isDeviceEncrypted()
                || SystemProperties.getBoolean("persist.sys.no_req_encrypt", false)) {

            // Notify the user once more that the admin will have full control over the profile,
            // then start provisioning.
            UserConsentDialog.newInstance(UserConsentDialog.PROFILE_OWNER)
                    .show(getFragmentManager(), "UserConsentDialogFragment");
        } else {
            Bundle resumeExtras = getIntent().getExtras();
            resumeExtras.putString(EXTRA_RESUME_TARGET, TARGET_PROFILE_OWNER);
            Intent encryptIntent = new Intent(this, EncryptDeviceActivity.class)
                    .putExtra(EXTRA_RESUME, resumeExtras);
            startActivityForResult(encryptIntent, ENCRYPT_DEVICE_REQUEST_CODE);
            // Continue in onActivityResult or after reboot.
        }
    }

    @Override
    public void onDialogConsent() {
        setupEnvironmentAndProvision();
    }

    @Override
    public void onDialogCancel() {
        // Do nothing.
    }

    private void setupEnvironmentAndProvision() {
        // Remove any pre-provisioning UI in favour of progress display
        BootReminder.cancelProvisioningReminder(this);

        // Check whether the current launcher supports managed profiles.
        if (!currentLauncherSupportsManagedProfiles()) {
            showCurrentLauncherInvalid();
        } else {
            startProfileOwnerProvisioning();
        }
    }

    private void pickLauncher() {
        Intent changeLauncherIntent = new Intent("android.settings.HOME_SETTINGS");
        changeLauncherIntent.putExtra(EXTRA_SUPPORT_MANAGED_PROFILES, true);
        startActivityForResult(changeLauncherIntent, CHANGE_LAUNCHER_REQUEST_CODE);
        // Continue in onActivityResult.
    }

    private void startProfileOwnerProvisioning() {
        Intent intent = new Intent(this, ProfileOwnerProvisioningActivity.class);
        intent.putExtras(getIntent());
        startActivityForResult(intent, PROVISIONING_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ENCRYPT_DEVICE_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled device encryption.");
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        } else if (requestCode == CHANGE_LAUNCHER_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                showCurrentLauncherInvalid();
            } else if (resultCode == RESULT_OK) {
                startProfileOwnerProvisioning();
            }
        }
        if (requestCode == PROVISIONING_REQUEST_CODE) {
            setResult(resultCode);
            finish();
        }
    }

    private void showCurrentLauncherInvalid() {
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setMessage(R.string.managed_provisioning_not_supported_by_launcher)
                .setNegativeButton(R.string.cancel_provisioning,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,int id) {
                                dialog.dismiss();
                                setResult(Activity.RESULT_CANCELED);
                                finish();
                            }
                        })
                .setPositiveButton(R.string.pick_launcher,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,int id) {
                                pickLauncher();
                            }
                        }).show();
    }

    public void showErrorAndClose(int resourceId, String logText) {
        ProvisionLogger.loge(logText);
        new AlertDialog.Builder(this)
                .setTitle(R.string.provisioning_error_title)
                .setMessage(getString(resourceId))
                .setCancelable(false)
                .setPositiveButton(R.string.device_owner_error_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // Close activity
                            ProfileOwnerPreProvisioningActivity.this
                                    .setResult(Activity.RESULT_CANCELED);
                            ProfileOwnerPreProvisioningActivity.this.finish();
                        }
                    }).show();
    }

    /**
     * @return The User id of an already existing managed profile or -1 if none
     * exists
     */
    int alreadyHasManagedProfile() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        List<UserInfo> profiles = userManager.getProfiles(getUserId());
        for (UserInfo userInfo : profiles) {
            if (userInfo.isManagedProfile()) {
                return userInfo.getUserHandle().getIdentifier();
            }
        }
        return -1;
    }

    /**
     * Builds a dialog that allows the user to remove an existing managed profile after they were
     * shown an additional warning.
     */
    private void showManagedProfileExistsDialog(
            final int existingManagedProfileUserId) {

        // Before deleting, show a warning dialog
        DialogInterface.OnClickListener warningListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Really delete the profile if the user clicks delete on the warning dialog.
                final DialogInterface.OnClickListener deleteListener =
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UserManager userManager =
                                (UserManager) getSystemService(Context.USER_SERVICE);
                        userManager.removeUser(existingManagedProfileUserId);
                        showStartProvisioningScreen();
                    }
                };
                buildDeleteManagedProfileDialog(
                        getString(R.string.sure_you_want_to_delete_profile),
                        deleteListener).show();
            }
        };

        buildDeleteManagedProfileDialog(
                getString(R.string.managed_profile_already_present),
                warningListener).show();
    }

    private AlertDialog buildDeleteManagedProfileDialog(String message,
            DialogInterface.OnClickListener deleteListener) {
        DialogInterface.OnClickListener cancelListener =
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ProfileOwnerPreProvisioningActivity.this.finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.delete_profile), deleteListener)
                .setNegativeButton(getString(R.string.cancel_delete_profile), cancelListener);

        return builder.create();
    }
    /**
     * Exception thrown when the provisioning has failed completely.
     *
     * We're using a custom exception to avoid catching subsequent exceptions that might be
     * significant.
     */
    private class ProvisioningFailedException extends Exception {
        public ProvisioningFailedException(String message) {
            super(message);
        }

        public ProvisioningFailedException(String message, Throwable t) {
            super(message, t);
        }
    }
}

