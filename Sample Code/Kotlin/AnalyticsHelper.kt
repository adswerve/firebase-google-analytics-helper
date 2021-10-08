package YOUR_PACKAGE_HERE

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import YOUR_PACKAGE_HERE.BuildConfig
import com.google.android.gms.analytics.GoogleAnalytics  // loaded by Google Tag Manager
import com.google.firebase.analytics.FirebaseAnalytics
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Helper class (object) to assist with common analytics implementation needs, including:
 *
 * - Defining event, parameter, and user property constants to prevent typos
 * - Adding standard parameters to every event
 * - Validating names/values before sending to Firebase
 * - Truncating string values to maximum supported lengths before sending to Firebase
 * - Logging events when the app goes into the foreground and background
 * - Logging event when app version updates (for Universal Analytics)
 *
 * This object must be configured before its methods are called, ideally in Application#onCreate.
 * Attempting to log events before calling AnalyticsHelper.configure() will result in an
 * IllegalStateException.
 *
 * For convenience, all commonly-used Firebase methods have identically-named methods here.
 *
 * Firebase validation/enforcement behavior can be controlled via the following properties:
 *
 * - validateInDebug (default: true)
 * - validateInProduction (default: false)
 * - sendValidationErrorEvents (default: false)
 * - throwOnValidationErrorsInDebug (default: false)
 * - truncateStringValues (default: true)
 *
 * This code is intended only to illustrate how you might create an analytics helper class.
 * It is not ready for production use as-is.
 *
 * The parts of this sample code that deal with Universal Analytics depend on the presence of
 * Google Tag Manager, which imports the Google Analytics library that provides access to the
 * GoogleAnalytics methods. If you are not using GTM, and not sending hits to GA360 (Universal
 * Analytics), you will need to remove code that references GoogleAnalytics.
 *
 * @author Chris Hubbard
 * @copyright Copyright (c) 2021 Adswerve. All rights reserved.
 */
object AnalyticsHelper {

    private val TAG = AnalyticsHelper::class.java.simpleName

    /**
     * Event name constants (define all custom event names here).
     */
    object Event {
        const val SCREEN_VIEW = "screen_view"
        const val APP_OPEN = "app_open"
        const val APP_CLOSE = "app_close"
        const val APP_UPDATE = "app_update_android"  // for use with Universal Analytics
        const val VALIDATION_ERROR = "error_validation"
    }

    /**
     * Event parameter name constants (define all custom parameter names here).
     */
    object Param {
        const val SCREEN_NAME = "screen_name"
        const val SCREEN_CLASS = "screen_class"
        const val TIMESTAMP = "timestamp"
        const val ERROR_MESSAGE = "error_message"
        const val PREVIOUS_APP_VERSION = "previous_app_version"
        const val ENGAGEMENT_TIME = "engagement_time"
    }

    /**
     * User property name constants (define all custom user property names here).
     */
    object UserProperty {
        const val ENVIRONMENT = "environment"
        const val APP_INSTANCE_ID = "app_instance_id"
        const val TIMEZONE_OFFSET = "timezone_offset"
    }

    /* Other constants */

    private const val ERROR_MESSAGE_FAILED_TO_INIT = "AnalyticsHelper must be initialized before use by calling AnalyticsHelper.configure(context)"

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
    @Synchronized
    fun configure(context: Context) {

        // store reference to FirebaseAnalytics
        firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)

        // register for Activity lifecycle callbacks to detect app open/close
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(foregroundMonitor)

        // refresh user properties that may have changed since last launch
        setUserProperty(UserProperty.TIMEZONE_OFFSET, timezoneOffset)
        firebaseAnalytics!!.appInstanceId
                .addOnSuccessListener { id -> setUserProperty(UserProperty.APP_INSTANCE_ID, id) }
                .addOnFailureListener {
                    // if first attempt fails, try again
                    firebaseAnalytics!!.appInstanceId.addOnSuccessListener { id -> setUserProperty(UserProperty.APP_INSTANCE_ID, id) }
                }

        // set an "environment" user property to allow GTM to send hits to the desired GA360 property (optional)
        // also allows test data to be filtered out in GA4/Firebase/BigQuery
        // Note: Due to the Firebase SDK's startup sequence, this user property may not be set until
        // after first_open and the first session_start, and potentially other automatic events that
        // occur immediately after the first launch. If this timing presents issues, the alternative
        // in traditional GA (Universal Analytics) is to use a GTM function call variable to supply
        // "environment" for each tag. (Currently there is no workaround for Firebase/GA4/BigQuery.)
        if (BuildConfig.DEBUG) {
            // default implementation determines environment based on build type, but
            // criteria can be made more sophisticated as needed
            setUserProperty(UserProperty.ENVIRONMENT, "test")
        } else {
            setUserProperty(UserProperty.ENVIRONMENT, "production")
        }

        // set GA dispatch interval (only applicable if using GTM to send data to Universal Analytics)
        if (BuildConfig.DEBUG) {
            GoogleAnalytics.getInstance(context).setLocalDispatchPeriod(1)  // 1 second
        }

        // custom app update event (because GTM cannot see the auto app_update event on Android)
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        val lastAppVersionCode = getStringPreference(prefs, Preference.APP_VERSION_CODE)
        if (null == lastAppVersionCode) {
            setStringPreference(prefs, Preference.APP_VERSION_CODE, java.lang.String.valueOf(BuildConfig.VERSION_CODE))
            setStringPreference(prefs, Preference.APP_VERSION_NAME, BuildConfig.VERSION_NAME)
        } else if (lastAppVersionCode != java.lang.String.valueOf(BuildConfig.VERSION_CODE)) {
            val lastAppVersionName = getStringPreference(prefs, Preference.APP_VERSION_NAME)
            val params = Bundle()
            params.putString(Param.PREVIOUS_APP_VERSION, lastAppVersionName)
            logEvent(Event.APP_UPDATE, params)
            setStringPreference(prefs, Preference.APP_VERSION_CODE, java.lang.String.valueOf(BuildConfig.VERSION_CODE))
            setStringPreference(prefs, Preference.APP_VERSION_NAME, BuildConfig.VERSION_NAME)
        }
    }


    /**************** Firebase helper methods ****************/


    /**
     * Wrapper for Firebase's logEvent method, providing an opportunity to validate the event,
     * enforce Firebase rules, append additional standard parameters, etc. before passing the
     * event to Firebase.
     *
     * @param name   Name of the event.
     * @param params Bundle of event parameters (optional).
     */
    fun logEvent(name: String, params: Bundle?) {

        // append additional parameters before logging the event (optional)
        // this could be done on every event, or just on certain events
        val newParams = params ?: Bundle()
        newParams.putString(Param.TIMESTAMP, timestamp)

        // validate event name and parameters before passing event to Firebase
        validateEvent(name, newParams)

        // log updated event to Firebase Analytics
        firebaseAnalytics!!.logEvent(name, if (truncateStringValues) truncateParams(newParams) else newParams)
    }

    /**
     * Wrapper for Firebase's setDefaultEventParameters method, providing an opportunity to
     * validate the parameters and enforce Firebase rules before passing them to Firebase.
     *
     * @param params Bundle of default event parameters (or null to clear them).
     */
    fun setDefaultEventParameters(params: Bundle?) {
        validateParameters("default parameters", params)
        firebaseAnalytics!!.setDefaultEventParameters(if (truncateStringValues) truncateParams(params) else params)
    }

    /**
     * Wrapper for Firebase's setUserProperty method, providing an opportunity to validate the
     * user property and enforce Firebase rules before passing it to Firebase.
     *
     * @param name  Name of the user property.
     * @param value Value of the user property (passing null clears the user property).
     */
    fun setUserProperty(name: String, value: String?) {
        validateUserProperty(name, value)
        firebaseAnalytics!!.setUserProperty(name, if (truncateStringValues) truncateUserProp(value) else value)
    }

    /**
     * Wrapper for Firebase's setUserId method, providing an opportunity to validate the ID value
     * before passing it to Firebase.
     *
     * This feature must be used in accordance with Google's Privacy Policy:
     * https://www.google.com/policies/privacy
     *
     * @param id Value to set as the Firebase User ID.
     */
    fun setUserId(id: String?) {
        validateUserId(id)
        firebaseAnalytics!!.setUserId(id)
    }

    /**
     * Wrapper for Firebase's setAnalyticsCollectionEnabled method. (For convenience.)
     *
     * Sets whether analytics collection is enabled for this app on this device. This setting
     * is persisted across app sessions. By default it is enabled.
     *
     * @param enabled A flag that enables or disables Analytics collection.
     */
    fun setAnalyticsCollectionEnabled(enabled: Boolean) {
        firebaseAnalytics!!.setAnalyticsCollectionEnabled(enabled)
    }

    /**
     * Wrapper for Firebase's resetAnalyticsData method. (For convenience.)
     *
     * Clears all analytics data for this app from the device and resets the app instance id.
     */
    fun resetAnalyticsData() {
        firebaseAnalytics!!.resetAnalyticsData()
    }

    /**
     * Truncates string parameter value to maximum supported length.
     *
     * @param value String parameter value to evaluate.
     * @return As much of the string as will fit in an event parameter.
     */
    fun truncateParam(value: String?): String? {
        return if (null != value && value.length > Validation.PARAMETER_VALUE_MAX_LENGTH) {
            value.substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH)
        } else {
            value
        }
    }

    /**
     * Truncates user property value to maximum supported length.
     *
     * @param value User property value to evaluate.
     * @return As much of the string as will fit in a user property.
     */
    fun truncateUserProp(value: String?): String? {
        return if (null != value && value.length > Validation.USER_PROPERTY_VALUE_MAX_LENGTH) {
            value.substring(0, Validation.USER_PROPERTY_VALUE_MAX_LENGTH)
        } else {
            value
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
    private fun truncateParams(params: Bundle?): Bundle? {
        if (null != params) {
            val paramNames = params.keySet()
            for (name in paramNames) {
                val value = params[name]
                if (value is String && value.toString().length > Validation.PARAMETER_VALUE_MAX_LENGTH) {
                    params.putString(name, value.toString().substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH))
                }
            }
        }
        return params
    }


    /**************** Validation/enforcement of Firebase/GA4 rules ****************/


    /**
     * Firebase/GA4 validation rules as defined at https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.
     */
    private object Validation {
        private const val EVENT_NAME_MAX_LENGTH = 40
        private const val PARAMETER_NAME_MAX_LENGTH = 40
        private const val USER_PROPERTY_NAME_MAX_LENGTH = 24
        private const val EVENT_NAME_PATTERN: String = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        private const val PARAMETER_NAME_PATTERN: String = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        private const val USER_PROPERTY_NAME_PATTERN: String = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        const val EVENT_MAX_PARAMETERS = 25
        const val PARAMETER_VALUE_MAX_LENGTH = 100
        const val USER_PROPERTY_VALUE_MAX_LENGTH = 36
        const val USER_ID_VALUE_MAX_LENGTH = 256
        val VALID_EVENT_NAME_REGEX: Pattern? = makeEventNameRegex()
        val VALID_PARAMETER_NAME_REGEX: Pattern? = makeParameterNameRegex()
        val VALID_USER_PROPERTY_NAME_REGEX: Pattern? = makeUserPropertyNameRegex()

        /**
         * Compiles regex pattern for Firebase event name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private fun makeEventNameRegex(): Pattern? {
            return try {
                val pattern = String.format(EVENT_NAME_PATTERN, EVENT_NAME_MAX_LENGTH - 1)
                Pattern.compile(pattern)
            } catch (ex: PatternSyntaxException) {
                if (BuildConfig.DEBUG) {
                    throw PatternSyntaxException("Invalid regex pattern for Firebase event name validation", ex.pattern, ex.index)
                }
                null
            }
        }

        /**
         * Compiles regex pattern for Firebase parameter name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private fun makeParameterNameRegex(): Pattern? {
            return try {
                val pattern = String.format(PARAMETER_NAME_PATTERN, PARAMETER_NAME_MAX_LENGTH - 1)
                Pattern.compile(pattern)
            } catch (ex: PatternSyntaxException) {
                if (BuildConfig.DEBUG) {
                    throw PatternSyntaxException("Invalid regex pattern for Firebase parameter name validation", ex.pattern, ex.index)
                }
                null
            }
        }

        /**
         * Compiles regex pattern for Firebase user property name validation using constants above.
         *
         * @return Pattern, or null on failure.
         * @throws PatternSyntaxException if pattern string is invalid. (Test builds only.)
         */
        private fun makeUserPropertyNameRegex(): Pattern? {
            return try {
                val pattern = String.format(USER_PROPERTY_NAME_PATTERN, USER_PROPERTY_NAME_MAX_LENGTH - 1)
                Pattern.compile(pattern)
            } catch (ex: PatternSyntaxException) {
                if (BuildConfig.DEBUG) {
                    throw PatternSyntaxException("Invalid regex pattern for Firebase user property name validation", ex.pattern, ex.index)
                }
                null
            }
        }

        /**
         * Indicates whether regex compiled successfully.
         *
         * @return True if regex patterns compiled.
         */
        fun isRegexReady(): Boolean {
            return null != VALID_EVENT_NAME_REGEX && null != VALID_PARAMETER_NAME_REGEX && null != VALID_USER_PROPERTY_NAME_REGEX
        }

    } // Validation class

    /**
     * Controls whether Firebase validation is performed in debug builds. Default is true.
     */
    var validateInDebug = true

    /**
     * Controls whether Firebase validation in performed in release builds. Default is false.
     *
     * If enabled, only sends custom error events to Firebase, no logging or exceptions.
     */
    var validateInProduction = false

    /**
     * Controls whether custom validation error events are sent to Firebase. Default is false.
     */
    var sendValidationErrorEvents = false

    /**
     * Controls whether InvalidArgument exceptions are thrown for Firebase validation errors
     * in debug builds. Default is false.
     */
    var throwOnValidationErrorsInDebug = false

    /**
     * Controls whether string values in event parameters and user properties are truncated to
     * the maximum lengths allowed before passing to Firebase. Default is true.
     *
     * While "validation" is about awareness of issues, this setting is about "enforcement", to
     * prevent Firebase from dropping parameters and user properties that exceed the allowable
     * lengths. If enabled, it applies regardless of build type or whether validation is enabled.
     *
     * Alternatively, use truncateParam() and truncateUserProp() to trim only those string values
     * that may potentially exceed the max.
     */
    var truncateStringValues = true

    /**
     * Determines whether validation rules should be applied.
     */
    private val isValidationEnabled: Boolean
        get() = ((validateInDebug && BuildConfig.DEBUG) || (validateInProduction && !BuildConfig.DEBUG)) && Validation.isRegexReady()

    /**
     * If validation is enabled, checks the event name, parameter count, and parameter names and
     * values against the Firebase/GA4 rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.Event
     *
     * @param name   Name of the event.
     * @param params Bundle of event parameters (optional).
     */
    @SuppressLint("DefaultLocale")
    private fun validateEvent(name: String, params: Bundle?) {
        if (isValidationEnabled) {
            // validate event name
            val m = Validation.VALID_EVENT_NAME_REGEX!!.matcher(name)
            val isInvalidName = !m.matches()
            if (isInvalidName) {
                val errorMessage = "Invalid event name '$name'"
                handleValidationError(errorMessage)
            }
            // validate parameter count
            val parameterCount = params?.size() ?: 0
            val isInvalidCount = parameterCount > Validation.EVENT_MAX_PARAMETERS
            if (isInvalidCount) {
                val errorMessage = "Too many parameters in event '$name': contains $parameterCount, max ${Validation.EVENT_MAX_PARAMETERS}"
                handleValidationError(errorMessage)
            }
            // validate parameters
            validateParameters(name, params)
        }
    }

    /**
     * If validation is enabled, checks each event parameter name and value against the
     * Firebase/GA4 rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.Param
     *
     * @param source Source of the parameters (for error message use).
     * @param params Bundle of event parameters (optional).
     */
    private fun validateParameters(source: String, params: Bundle?) {
        if (isValidationEnabled && null != params) {
            val paramNames = params.keySet()
            for (name in paramNames) {
                // validate parameter name
                val m = Validation.VALID_PARAMETER_NAME_REGEX!!.matcher(name)
                val isInvalidName = !m.matches()
                if (isInvalidName) {
                    val errorMessage = "Invalid parameter name '$name' in '$source'"
                    handleValidationError(errorMessage)
                }
                // validate parameter value
                val value = params[name]
                if ("items" == name) {
                    // special handling required for Ecommerce "items" parameter, which may contain
                    // an ArrayList<Bundle> or Array<Bundle> of products
                    if (value is ArrayList<*>) {
                        for (product in value) {
                            if (product is Bundle) {
                                validateParameters("$source [items]", product)
                            }
                        }
                    } else if (value is Array<*>) {
                        for (product in value) {
                            if (product is Bundle) {
                                validateParameters("$source [items]", product)
                            }
                        }
                    }
                } else {
                    // normal event parameter
                    val isInvalidValue = value is String && value.toString().length > Validation.PARAMETER_VALUE_MAX_LENGTH
                    if (isInvalidValue) {
                        val errorMessage = "Value too long for parameter '$name' in '$source': $value"
                        handleValidationError(errorMessage)
                    }
                }
            }
        }
    }

    /**
     * If validation is enabled, checks the user property name and value against the Firebase/GA4
     * rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics.UserProperty
     *
     * @param name  Name of the user property.
     * @param value Value of the user property.
     */
    private fun validateUserProperty(name: String, value: Any?) {
        if (isValidationEnabled) {
            // validate user property name
            val m = Validation.VALID_USER_PROPERTY_NAME_REGEX!!.matcher(name)
            val isInvalidName = !m.matches()
            if (isInvalidName) {
                val errorMessage = String.format("Invalid user property name '%s'", name)
                handleValidationError(errorMessage)
            }
            // validate user property value
            val isInvalidValue = value is String && (value.length > Validation.USER_PROPERTY_VALUE_MAX_LENGTH)
            if (isInvalidValue) {
                val errorMessage = "Value too long for user property '$name': $value"
                handleValidationError(errorMessage)
            }
        }
    }

    /**
     * If validation is enabled, checks the user ID against the Firebase/GA4 rules.
     *
     * See: https://firebase.google.com/docs/reference/android/com/google/firebase/analytics/FirebaseAnalytics
     *
     * @param id Value of the user ID.
     */
    private fun validateUserId(id: String?) {
        if (isValidationEnabled && null != id) {
            val isInvalidValue = id.length > Validation.USER_ID_VALUE_MAX_LENGTH
            if (isInvalidValue) {
                val errorMessage = "User ID is too long: $id"
                handleValidationError(errorMessage)
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
    private fun handleValidationError(errorMessage: String) {
        // log error (debug builds only)
        if (BuildConfig.DEBUG) {
            Log.e(TAG, errorMessage)
        }
        // error event option (available in debug and production)
        if (sendValidationErrorEvents) {
            val eventMessage: String
            if (errorMessage.length > Validation.PARAMETER_VALUE_MAX_LENGTH) {
                // truncate message
                eventMessage = "${errorMessage.substring(0, Validation.PARAMETER_VALUE_MAX_LENGTH - 3)}..."
            } else {
                eventMessage = errorMessage
            }
            val errorParams = Bundle()
            errorParams.putString(Param.ERROR_MESSAGE, eventMessage)
            firebaseAnalytics!!.logEvent(Event.VALIDATION_ERROR, errorParams)
        }
        // IllegalArgumentException option (debug builds only)
        // note that the exception will prevent an error event (see above) from being sent
        require(!(throwOnValidationErrorsInDebug) && BuildConfig.DEBUG) { errorMessage }
    }


    /**************** Private SharedPreferences ****************/


    /**
     * SharedPreferences keys.
     */
    private object Preference {
        const val APP_VERSION_CODE = "app_version_code"
        const val APP_VERSION_NAME = "app_version_name"
    }

    /**
     * Retrieves a String value from SharedPreferences.
     *
     * @param prefs SharedPreferences reference.
     * @param key   Name of the preference.
     * @return The preference value if it exists, or null if not.
     * @throws IllegalStateException if called before AnalyticsHelper#configure(context).
     */
    private fun getStringPreference(prefs: SharedPreferences?, key: String): String? {
        checkNotNull(prefs) { ERROR_MESSAGE_FAILED_TO_INIT }
        return prefs.getString(key, null)
    }

    /**
     * Saves the String value to SharedPreferences.
     *
     * @param prefs SharedPreferences reference.
     * @param key   Name of the preference.
     * @param value String value to be saved.
     * @throws IllegalStateException if called before AnalyticsHelper#configure(context).
     */
    private fun setStringPreference(prefs: SharedPreferences?, key: String, value: String) {
        checkNotNull(prefs) { ERROR_MESSAGE_FAILED_TO_INIT }
        val editor = prefs.edit()
        editor.putString(key, value)
        editor.apply()
    }


    /**************** Private convenience properties ****************/


    /**
     * Detects when app enters foreground/background. (See below.)
     */
    private val foregroundMonitor = ForegroundMonitor()

    /**
     * Reference to the FirebaseAnalytics instance.
     * 
     * @throws IllegalStateException if referenced before AnalyticsHelper#configure(context).
     */
    private var firebaseAnalytics: FirebaseAnalytics? = null
        get() {
            checkNotNull(field) { ERROR_MESSAGE_FAILED_TO_INIT }
            return field
        }

    /**
     * String representation of current timestamp.
     */
    @get:SuppressLint("SimpleDateFormat")
    private val timestamp: String
        get() {
            val format = "yyyy-MM-dd HH:mm:ss.SSS 'GMT'Z '('z')'" // 2020-02-11 11:26:02.868 GMT-0800 (PST)
            val formatter = SimpleDateFormat(format)
            formatter.timeZone = TimeZone.getDefault()
            return formatter.format(Date(System.currentTimeMillis()))
        }

    /**
     * Device's current timezone offset in hours vs GMT (e.g., -8.0, -7.0, 2.0, 1.0).
     */
    @get:SuppressLint("DefaultLocale")
    private val timezoneOffset: String
        get() {
            val offsetInHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000.0 * 60 * 60)
            return String.format("%1.1f", offsetInHours)
        }


    /**************** Supporting classes ****************/


    /**
     * This class listens for changes in the Activity lifecycle for the purpose of detecting when
     * the app goes into the foreground/background so it can log the corresponding event.
     *
     * Note that Firebase has its own automatic app_foreground and app_background events; however,
     * these are logged by Firebase's background service, downstream of Google Tag Manager and
     * thus are unavailable to GTM. The are also not visible in Firebase/GA4/BigQuery.
     */
    private class ForegroundMonitor : ActivityLifecycleCallbacks {
        private var activityReferences = 0
        private var isActivityChangingConfigurations = false
        private var engagementStartTime: Long = 0

        override fun onActivityStarted(activity: Activity) {
            if (++activityReferences == 1 && !isActivityChangingConfigurations) {
                // App entered foreground
                logEvent(Event.APP_OPEN, null)
                engagementStartTime = System.currentTimeMillis()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            isActivityChangingConfigurations = activity.isChangingConfigurations
            if (--activityReferences == 0 && !isActivityChangingConfigurations) {
                // App entered background
                val durationInSeconds = (System.currentTimeMillis() - engagementStartTime) / 1000.0
                val params = Bundle()
                params.putDouble(Param.ENGAGEMENT_TIME, durationInSeconds)
                logEvent(Event.APP_CLOSE, params)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}

    } // ForegroundMonitor

} // AnalyticsHelper
