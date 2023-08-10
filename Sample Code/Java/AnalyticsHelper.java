package YOUR_PACKAGE_HERE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import YOUR_PACKAGE_HERE.BuildConfig;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helper class to assist with common GA4 analytics implementation needs, including:
 *
 * - Defining event, parameter, and user property constants to prevent typos
 * - Adding standard parameters to every event
 * - Validating names/values before sending to GA4/Firebase
 * - Truncating string values to maximum supported lengths before sending to GA4/Firebase
 * - Logging events when the app goes into the foreground and background
 *
 * This class cannot be instantiated. All methods are static class methods. However, it must be
 * configured before its methods are called, ideally in Application#onCreate. Attempting to log
 * events before calling AnalyticsHelper.configure() will result in an IllegalStateException.
 *
 * For convenience, all commonly-used GA4/Firebase methods have identically-named methods here.
 *
 * GA4/Firebase validation/enforcement behavior can be controlled via the following methods:
 *
 * - setValidateInDebug (default: true)
 * - setValidateInProduction (default: false)
 * - setSendValidationErrorEvents (default: false)
 * - setThrowOnValidationErrorsInDebug (default: false)
 * - setTruncateStringValues (default: true)
 *
 * This code is intended only to illustrate how you might create an analytics helper class.
 * It is not ready for production use as-is.
 *
 * @author Chris Hubbard
 * @copyright Copyright (c) 2023 Adswerve. All rights reserved.
 */
public class AnalyticsHelper {

    private static final String TAG = AnalyticsHelper.class.getSimpleName();

    /**
     * Event name constants (define all custom event names here).
     */
    public static class Event {
        public static final String SCREEN_VIEW = "screen_view";
        private static final String APP_OPEN = "app_open";
        private static final String APP_CLOSE = "app_close";
        private static final String VALIDATION_ERROR = "error_validation";
    }

    /**
     * Event parameter name constants (define all custom parameter names here).
     */
    public static class Param {
        public static final String SCREEN_NAME = "screen_name";
        public static final String SCREEN_CLASS = "screen_class";
        private static final String TIMESTAMP = "timestamp";
        private static final String ERROR_MESSAGE = "error_message";
        private static final String ENGAGEMENT_TIME = "engagement_time";
    }

    /**
     * User property name constants (define all custom user property names here).
     */
    public static class UserProperty {
        private static final String CLIENT_ID_2 = "client_id_2";
        private static final String TIMEZONE_OFFSET = "timezone_offset";
    }

    /* Other constants. */

    private static final String ERROR_MESSAGE_FAILED_TO_INIT = "AnalyticsHelper must be initialized before use by calling AnalyticsHelper.configure(context)";

    /* Private static variables. */

    private static FirebaseAnalytics sFirebaseAnalytics = null;
    private static final ForegroundMonitor sForegroundMonitor = new ForegroundMonitor();

    /* Private constructor to prevent instantiation. */
    private AnalyticsHelper() {
    }

    /**
     * Configures this helper class and provides an opportunity to set initial state and refresh any
     * user properties that may have changed since the previous launch.
     *
     * This method must be called before other methods in this class are used, ideally in
     * Application#onCreate. Attempting to log events, etc. via this helper before calling
     * configure() will result in an IllegalStateException.
     *
     * @param context Context to retrieve FirebaseAnalytics and SharedPreferences.
     */
    public synchronized static void configure(@NonNull final Context context) {

        // store reference to FirebaseAnalytics
        sFirebaseAnalytics = FirebaseAnalytics.getInstance(context.getApplicationContext());

        // register for Activity lifecycle callbacks to detect app open/close
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(sForegroundMonitor);

        // refresh user properties that may have changed since last launch
        setUserProperty(UserProperty.TIMEZONE_OFFSET, getTimezoneOffset());
        sFirebaseAnalytics.getAppInstanceId()
                .addOnSuccessListener(id -> setUserProperty(UserProperty.CLIENT_ID_2, id))
                .addOnFailureListener(e -> {
                    // if first attempt fails, try again
                    sFirebaseAnalytics.getAppInstanceId()
                            .addOnSuccessListener(id -> setUserProperty(UserProperty.CLIENT_ID_2, id));
                });

    }


    /**************** GA4/Firebase helper methods ****************/


    /**
     * Wrapper for GA4/Firebase's logEvent method, providing an opportunity to validate the event and
     * append additional standard parameters before passing the event to GA4/Firebase.
     *
     * @param name   Name of the event.
     * @param params Bundle of event parameters (optional).
     */
    public static void logEvent(@NonNull String name, @Nullable Bundle params) {

        // append additional parameters before logging the event (optional)
        // this could be done on every event, or just on certain events
        params = (null == params) ? new Bundle() : params;
        params.putString(Param.TIMESTAMP, getTimestamp()); // example: append timestamp parameter

        // validate event name and parameters before passing event to Firebase
        validateEvent(name, params);

        // log updated event to Firebase Analytics
        getFirebaseAnalytics().logEvent(name, sTruncateStringValues ? truncateParams(params) : params);
    }

    /**
     * Wrapper for GA4/Firebase's setDefaultEventParameters method, providing an opportunity to
     * validate the parameters before passing them to GA4/Firebase.
     *
     * @param params Bundle of default event parameters (or null to clear them).
     */
    public static void setDefaultEventParameters(@Nullable Bundle params) {
        validateParameters("default parameters", params);
        getFirebaseAnalytics().setDefaultEventParameters(sTruncateStringValues ? truncateParams(params) : params);
    }

    /**
     * Wrapper for GA4/Firebase's setUserProperty method, providing an opportunity to
     * validate the user property name and value before passing it to GA4/Firebase.
     *
     * @param name  Name of the user property.
     * @param value Value of the user property (passing null clears the user property).
     */
    public static void setUserProperty(@NonNull String name, @Nullable String value) {
        validateUserProperty(name, value);
        getFirebaseAnalytics().setUserProperty(name, sTruncateStringValues ? truncateUserProp(value) : value);
    }

    /**
     * Wrapper for GA4/Firebase's setUserId method, providing an opportunity to
     * validate the ID value before passing it to GA4/Firebase.
     *
     * This feature must be used in accordance with Google's Privacy Policy:
     * https://www.google.com/policies/privacy
     *
     * @param id Value to set as the GA4 User ID.
     */
    public static void setUserId(@Nullable String id) {
        validateUserId(id);
        getFirebaseAnalytics().setUserId(id);
    }

    /**
     * Wrapper for GA4/Firebase's setAnalyticsCollectionEnabled method. (For convenience.)
     *
     * Sets whether analytics collection is enabled for this app on this device. This setting
     * is persisted across app sessions. By default it is enabled.
     *
     * @param enabled A flag that enables or disables Analytics collection.
     */
    public static void setAnalyticsCollectionEnabled(boolean enabled) {
        getFirebaseAnalytics().setAnalyticsCollectionEnabled(enabled);
    }

    /**
     * Wrapper for GA4/Firebase's resetAnalyticsData method. (For convenience.)
     *
     * Clears all analytics data for this app from the device and resets the app instance id.
     */
    public static void resetAnalyticsData() {
        getFirebaseAnalytics().resetAnalyticsData();
    }

    /**
     * Truncates string parameter value to maximum supported length.
     *
     * @param value String parameter value to evaluate.
     * @return As much of the string as will fit in an event parameter.
     */
    public static String truncateParam(String value) {
        if (null != value && value.length() > Validation.PARAMETER_VALUE_MAX_LENGTH) {
            return value.substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH);
        } else {
            return value;
        }
    }

    /**
     * Truncates user property value to maximum supported length.
     *
     * @param value User property value to evaluate.
     * @return As much of the string as will fit in a user property.
     */
    public static String truncateUserProp(String value) {
        if (null != value && value.length() > Validation.USER_PROPERTY_VALUE_MAX_LENGTH) {
            return value.substring(0, Validation.USER_PROPERTY_VALUE_MAX_LENGTH);
        } else {
            return value;
        }
    }

    /**
     * Truncates string parameter values to maximum supported length.
     *
     * Because this iterates over the entire parameter bundle, it's more efficient to use the
     * public truncateParam() method on just those string values that might exceed the max, but
     * this method can ensure overall compliance if desired.
     *
     * @param params Parameter bundle to evaluate.
     * @return Parameter bundle with string values shortened (if needed).
     */
    private static Bundle truncateParams(Bundle params) {
        if (null != params) {
            Set<String> paramNames = params.keySet();
            for (String name : paramNames) {
                Object value = params.get(name);
                if ((value instanceof String) && (value.toString().length() > Validation.PARAMETER_VALUE_MAX_LENGTH)) {
                    params.putString(name, value.toString().substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH));
                }
            }
        }
        return params;
    }


    /**************** Validation/enforcement of GA4/Firebase rules ****************/


    /**
     * GA4/Firebase validation rules as defined at https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.
     */
    private static class Validation {
        private static final int EVENT_MAX_PARAMETERS = 25;
        private static final int EVENT_NAME_MAX_LENGTH = 40;
        private static final int PARAMETER_NAME_MAX_LENGTH = 40;
        private static final int PARAMETER_VALUE_MAX_LENGTH = 100;
        private static final int USER_PROPERTY_NAME_MAX_LENGTH = 24;
        private static final int USER_PROPERTY_VALUE_MAX_LENGTH = 36;
        private static final int USER_ID_VALUE_MAX_LENGTH = 256;
        private static final String EVENT_NAME_PATTERN = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";
        private static final String PARAMETER_NAME_PATTERN = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";
        private static final String USER_PROPERTY_NAME_PATTERN = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$";
        private static final Pattern VALID_EVENT_NAME_REGEX = makeEventNameRegex();
        private static final Pattern VALID_PARAMETER_NAME_REGEX = makeParameterNameRegex();
        private static final Pattern VALID_USER_PROPERTY_NAME_REGEX = makeUserPropertyNameRegex();

        /**
         * Compiles regex pattern for GA4/Firebase event name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private static Pattern makeEventNameRegex() {
            try {
                @SuppressLint("DefaultLocale")
                String pattern = String.format(EVENT_NAME_PATTERN, EVENT_NAME_MAX_LENGTH - 1);
                return Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                if (BuildConfig.DEBUG) {
                    throw new PatternSyntaxException("Invalid regex pattern for Firebase event name validation", ex.getPattern(), ex.getIndex());
                }
                return null;
            }
        }

        /**
         * Compiles regex pattern for GA4/Firebase parameter name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private static Pattern makeParameterNameRegex() {
            try {
                @SuppressLint("DefaultLocale")
                String pattern = String.format(PARAMETER_NAME_PATTERN, PARAMETER_NAME_MAX_LENGTH - 1);
                return Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                if (BuildConfig.DEBUG) {
                    throw new PatternSyntaxException("Invalid regex pattern for Firebase parameter name validation", ex.getPattern(), ex.getIndex());
                }
                return null;
            }
        }

        /**
         * Compiles regex pattern for GA4/Firebase user property name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private static Pattern makeUserPropertyNameRegex() {
            try {
                @SuppressLint("DefaultLocale")
                String pattern = String.format(USER_PROPERTY_NAME_PATTERN, USER_PROPERTY_NAME_MAX_LENGTH - 1);
                return Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                if (BuildConfig.DEBUG) {
                    throw new PatternSyntaxException("Invalid regex pattern for Firebase user property name validation", ex.getPattern(), ex.getIndex());
                }
                return null;
            }
        }

        /**
         * Indicates whether regex compiled successfully.
         *
         * @return True if regex patterns compiled.
         */
        private static boolean isRegexReady() {
            return null != VALID_EVENT_NAME_REGEX && null != VALID_PARAMETER_NAME_REGEX && null != VALID_USER_PROPERTY_NAME_REGEX;
        }

    } // Validation class

    /* Private class variables for validation/enforcement of GA4/Firebase rules (see setters below). */

    private static boolean sValidateInDebug = true;
    private static boolean sValidateInProduction = false;
    private static boolean sSendValidationErrorEvents = false;
    private static boolean sThrowOnValidationErrorsInDebug = false;
    private static boolean sTruncateStringValues = true;

    /**
     * Controls whether validation is performed in debug builds. Default is true.
     */
    public static void setValidateInDebug(boolean b) {
        sValidateInDebug = b;
    }

    /**
     * Controls whether validation is performed in release builds. Default is false.
     *
     * If enabled, only sends custom error events to GA4/Firebase, no logging or exceptions.
     */
    public static void setValidateInProduction(boolean b) {
        sValidateInProduction = b;
    }

    /**
     * Controls whether custom validation error events are sent to GA4/Firebase. Default is false.
     */
    public static void setSendValidationErrorEvents(boolean b) {
        sSendValidationErrorEvents = b;
    }

    /**
     * Controls whether InvalidArgument exceptions are thrown for GA4/Firebase validation errors
     * in debug builds. Default is false.
     */
    public static void setThrowOnValidationErrorsInDebug(boolean b) {
        sThrowOnValidationErrorsInDebug = b;
    }

    /**
     * Controls whether string values in event parameters and user properties are truncated to
     * the maximum lengths allowed before passing to GA4/Firebase. Default is true.
     *
     * While "validation" is about awareness of issues, this setting is about "enforcement", to
     * prevent GA4/Firebase from dropping parameters and user properties that exceed the allowable
     * lengths. If enabled, it applies regardless of build type or whether validation is enabled.
     *
     * Alternatively, use truncateParam() and truncateUserProp() to trim only those string values
     * that may potentially exceed the max.
     */
    public static void setTruncateStringValues(boolean b) {
        sTruncateStringValues = b;
    }

    /**
     * Determines whether validation rules should be applied.
     *
     * @return True if validation should be applied.
     */
    private static boolean isValidationEnabled() {
        return ((sValidateInDebug && BuildConfig.DEBUG) || (sValidateInProduction && !BuildConfig.DEBUG)) && Validation.isRegexReady();
    }

    /**
     * If validation is enabled, checks the event name, parameter count, and parameter names and
     * values against the GA4/Firebase rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.Event
     *
     * @param name   Name of the event.
     * @param params Bundle of event parameters (optional).
     */
    @SuppressLint("DefaultLocale")
    private static void validateEvent(String name, Bundle params) {
        if (isValidationEnabled()) {
            // validate event name
            Matcher m = Validation.VALID_EVENT_NAME_REGEX.matcher(name);
            boolean isInvalidName = !m.matches();
            if (isInvalidName) {
                String errorMessage = String.format("Invalid event name '%s'", name);
                handleValidationError(errorMessage);
            }
            // validate parameter count
            int parameterCount = (null == params) ? 0 : params.size();
            boolean isInvalidCount = parameterCount > Validation.EVENT_MAX_PARAMETERS;
            if (isInvalidCount) {
                String errorMessage = String.format("Too many parameters in event '%s': contains %d, max %d", name, parameterCount, Validation.EVENT_MAX_PARAMETERS);
                handleValidationError(errorMessage);
            }
            // validate parameters
            validateParameters(name, params);
        }
    }

    /**
     * If validation is enabled, checks each event parameter name and value against the
     * GA4/Firebase rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.Param
     *
     * @param source Source of the parameters (for error message use).
     * @param params Bundle of event parameters (optional).
     */
    private static void validateParameters(String source, Bundle params) {
        if (isValidationEnabled() && null != params) {
            Set<String> paramNames = params.keySet();
            for (String name : paramNames) {
                // validate parameter name
                Matcher m = Validation.VALID_PARAMETER_NAME_REGEX.matcher(name);
                boolean isInvalidName = !m.matches();
                if (isInvalidName) {
                    String errorMessage = String.format("Invalid parameter name '%s' in '%s'", name, source);
                    handleValidationError(errorMessage);
                }
                // validate parameter value
                Object value = params.get(name);
                if ("items".equals(name)) {
                    // special handling required for Ecommerce "items" parameter, which may contain
                    // an ArrayList<Bundle> or Parcelable[] of products
                    if (value instanceof ArrayList) {
                        for (Object product : (ArrayList) value) {
                            if (product instanceof Bundle) {
                                validateParameters(source + " [items]", (Bundle) product);
                            }
                        }
                    } else if (value instanceof Parcelable[]) {
                        for (Object product : (Parcelable[]) value) {
                            if (product instanceof Bundle) {
                                validateParameters(source + " [items]", (Bundle) product);
                            }
                        }
                    }
                } else {
                    // normal event parameter
                    boolean isInvalidValue = (value instanceof String) && value.toString().length() > Validation.PARAMETER_VALUE_MAX_LENGTH;
                    if (isInvalidValue) {
                        String errorMessage = String.format("Value too long for parameter '%s' in '%s': %s", name, source, value);
                        handleValidationError(errorMessage);
                    }
                }
            }
        }
    }

    /**
     * If validation is enabled, checks the user property name and value against the GA4/Firebase
     * rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.UserProperty
     *
     * @param name  Name of the user property.
     * @param value Value of the user property.
     */
    private static void validateUserProperty(String name, Object value) {
        if (isValidationEnabled()) {
            // validate user property name
            assert Validation.VALID_USER_PROPERTY_NAME_REGEX != null;
            Matcher m = Validation.VALID_USER_PROPERTY_NAME_REGEX.matcher(name);
            boolean isInvalidName = !m.matches();
            if (isInvalidName) {
                String errorMessage = String.format("Invalid user property name '%s'", name);
                handleValidationError(errorMessage);
            }
            // validate user property value
            boolean isInvalidValue = (value instanceof String) && (value.toString().length() > Validation.USER_PROPERTY_VALUE_MAX_LENGTH);
            if (isInvalidValue) {
                String errorMessage = String.format("Value too long for user property '%s': %s", name, value);
                handleValidationError(errorMessage);
            }
        }
    }

    /**
     * If validation is enabled, checks the user ID against the GA4/Firebase rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics
     *
     * @param id Value of the user ID.
     */
    private static void validateUserId(String id) {
        if (isValidationEnabled() && null != id) {
            boolean isInvalidValue = id.length() > Validation.USER_ID_VALUE_MAX_LENGTH;
            if (isInvalidValue) {
                String errorMessage = String.format("User ID is too long: %s", id);
                handleValidationError(errorMessage);
            }
        }
    }

    /**
     * Handles the validation error by logging it, sending a custom error event, and/or
     * throwing an IllegalArgumentException.
     *
     * Behavior is controlled via the "validation" configuration properties. (Logging and
     * exceptions are limited to debug builds only.)
     *
     * @param errorMessage The message to be communicated.
     * @throws IllegalArgumentException if exception option is enabled. (Test builds only.)
     */
    private static void handleValidationError(String errorMessage) {
        // log error (debug builds only)
        if (BuildConfig.DEBUG) {
            Log.e(TAG, errorMessage);
        }
        // error event option (available in debug and production)
        if (sSendValidationErrorEvents) {
            if (errorMessage.length() > Validation.PARAMETER_VALUE_MAX_LENGTH) {
                // truncate message
                errorMessage = String.format("%s...", errorMessage.substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH - 3));
            }
            Bundle errorParams = new Bundle();
            errorParams.putString(Param.ERROR_MESSAGE, errorMessage);
            getFirebaseAnalytics().logEvent(Event.VALIDATION_ERROR, errorParams);
        }
        // IllegalArgumentException option (debug builds only)
        // note that the exception will prevent an error event (see above) from being sent
        if (sThrowOnValidationErrorsInDebug && BuildConfig.DEBUG) {
            throw new IllegalArgumentException(errorMessage);
        }
    }


    /**************** Private convenience methods ****************/


    /**
     * Reference to the FirebaseAnalytics instance.
     *
     * @return FirebaseAnalytics instance.
     * @throws IllegalStateException if called before AnalyticsHelper#configure(context).
     */
    private static FirebaseAnalytics getFirebaseAnalytics() {
        if (null == sFirebaseAnalytics) {
            throw new IllegalStateException(ERROR_MESSAGE_FAILED_TO_INIT);
        }
        return sFirebaseAnalytics;
    }

    /**
     * Returns the current timestamp.
     *
     * @return String representation of current timestamp.
     */
    @SuppressLint("SimpleDateFormat")
    private static String getTimestamp() {
        String format = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"; // 2022-10-20T18:51:30.974-07:00 (ISO 8601 format)
        SimpleDateFormat formatter = new SimpleDateFormat(format);
        formatter.setTimeZone(TimeZone.getDefault());
        return formatter.format(new Date(System.currentTimeMillis()));
    }

    /**
     * Returns the device's current timezone offset in hours vs GMT (e.g., -8.0, -7.0, 2.0, 1.0).
     *
     * @return String representation of current timezone offset.
     */
    @SuppressLint("DefaultLocale")
    private static String getTimezoneOffset() {
        double offsetInHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000.0 * 60 * 60);
        return String.format("%1.1f", offsetInHours);
    }


    /**************** Supporting classes ****************/


    /**
     * This class listens for changes in the Activity lifecycle for the purpose of detecting when
     * the app goes into the foreground/background so it can log the corresponding event.
     *
     * Note that GA4/Firebase has its own automatic app_foreground and app_background events; however, these
     * cannot be modified and are not visible in GA4/Firebase/BigQuery.
     */
    private static class ForegroundMonitor implements Application.ActivityLifecycleCallbacks {
        private int mActivityReferences = 0;
        private boolean mIsActivityChangingConfigurations = false;
        private long mEngagementStartTime;

        @Override
        public void onActivityStarted(Activity activity) {
            if (++mActivityReferences == 1 && !mIsActivityChangingConfigurations) {
                // App entered foreground
                logEvent(Event.APP_OPEN, null);
                mEngagementStartTime = System.currentTimeMillis();
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
            mIsActivityChangingConfigurations = activity.isChangingConfigurations();
            if (--mActivityReferences == 0 && !mIsActivityChangingConfigurations) {
                // App entered background
                double durationInSeconds = (System.currentTimeMillis() - mEngagementStartTime) / 1000.0;
                Bundle params = new Bundle();
                params.putDouble(Param.ENGAGEMENT_TIME, durationInSeconds);
                logEvent(Event.APP_CLOSE, params);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }

    } // ForegroundMonitor

} // AnalyticsHelper
