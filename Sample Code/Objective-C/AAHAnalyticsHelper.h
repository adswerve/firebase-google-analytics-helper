/**
 * AnalyticsHelper.h
 *
 * Helper class to assist with common analytics implementation needs, including:
 *
 * - Defining event, parameter, and user property constants to prevent typos
 * - Adding standard parameters to every event, etc.
 * - Validating names/values before sending to Firebase
 * - Truncating string values to maximum supported lengths before sending to Firebase
 * - Enabling DebugView in builds not launched directly from Xcode
 * - Sending buffered hits when app goes into the background (Universal Analytics)
 *
 * This class does not need to  be instantiated. All methods are static class methods. For convenience,
 * all commonly-used Firebase methods have identically-named methods here.
 *
 * Firebase validation/enforcement behavior can be controlled via the following methods:
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
 * The parts of this sample code that deal with Universal Analytics depend on the presence of
 * Google Tag Manager, which imports the Google Analytics library that provides access to the
 * GAI methods. If you are not using GTM, and not sending hits to GA360 (Universal Analytics),
 * you will need to remove code that references GAI.
 *
 * @author Chris Hubbard
 * @copyright Copyright (c) 2021 Adswerve. All rights reserved.
 */

#ifndef AAHAnalyticsHelper_h
#define AAHAnalyticsHelper_h

@import Foundation;

/** Event name constants (define all custom event names here). */
static NSString *const _Nonnull kAAHAnalyticsHelperEventScreenView NS_SWIFT_NAME(AnalyticsHelperEventScreenView) = @"screen_view";
static NSString *const _Nonnull kAAHAnalyticsHelperEventValidationError NS_SWIFT_NAME(AnalyticsHelperEventValidationError) = @"error_validation";

/** Event parameter name constants (define all custom parameter names here). */
static NSString *const _Nonnull kAAHAnalyticsHelperParameterScreenName NS_SWIFT_NAME(AnalyticsHelperParameterScreenName) = @"screen_name";
static NSString *const _Nonnull kAAHAnalyticsHelperParameterScreenClass NS_SWIFT_NAME(AnalyticsHelperParameterScreenClass) = @"screen_class";
static NSString *const _Nonnull kAAHAnalyticsHelperParameterTimestamp NS_SWIFT_NAME(AnalyticsHelperParameterTimestamp) = @"timestamp";
static NSString *const _Nonnull kAAHAnalyticsHelperParameterErrorMessage NS_SWIFT_NAME(AnalyticsHelperParameterErrorMessage) = @"error_message";

/** User property name constants (define all custom user property names here). */
static NSString *const _Nonnull kAAHAnalyticsHelperUserPropertyEnvironment NS_SWIFT_NAME(AnalyticsHelperUserPropertyEnvironment) = @"environment";
static NSString *const _Nonnull kAAHAnalyticsHelperUserPropertyAppInstanceID NS_SWIFT_NAME(AnalyticsHelperUserPropertyAppInstanceID) = @"app_instance_id";
static NSString *const _Nonnull kAAHAnalyticsHelperUserPropertyTimezoneOffset NS_SWIFT_NAME(AnalyticsHelperUserPropertyTimezoneOffset) = @"timezone_offset";

@class AAHAnalyticsHelper;

@interface AAHAnalyticsHelper : NSObject {
}

// MARK: - Configuration

/**
 * Configures this helper class and provides an opportunity to set initial state and refresh any user properties
 * that may have changed since the previous launch.
 *
 * This method will be called automatically if needed when using the Firebase helper methods below; however,
 * to set initial state as early as possible in the app's startup process, calling this manually immediately after
 * calling `[FIRApp configure]` is recommended.
 */
+ (void)configure NS_SWIFT_NAME(configure());

// MARK: - Firebase helpers

/**
 * Wrapper for Firebase's logEventWithName method, providing an opportunity to validate the event and
 * append additional standard parameters before passing the event to Firebase.
 *
 * @param name The name of the event.
 * @param parameters Optional dictionary of event parameters.
 */
+ (void)logEventWithName:(nonnull NSString *)name parameters:(nullable NSDictionary<NSString *, id> *)parameters
NS_SWIFT_NAME(logEvent(_:parameters:));

/**
 * Wrapper for Firebase's setDefaultEventParameters method, providing an opportunity to
 * validate the parameters before passing them to Firebase.
 *
 * @param parameters Dictionary of event parameters (or nil to clear them).
 */
+ (void)setDefaultEventParameters:(nullable NSDictionary<NSString *, id> *)parameters
NS_SWIFT_NAME(setDefaultEventParameters(_:));

/**
 * Wrapper for Firebase's setUserProperty method, providing an opportunity to
 * validate the user property name and value before passing it to Firebase.
 *
 * @param value The value of the user property (passing nil clears the user property).
 * @param name The name of the user property to set.
 */
+ (void)setUserPropertyString:(nullable NSString *)value forName:(nonnull NSString *)name
NS_SWIFT_NAME(setUserProperty(_:forName:));

/**
 * Wrapper for Firebase's setUserID method, providing an opportunity to
 * validate the ID value before passing it to Firebase.
 *
 * This feature must be used in accordance with Google's Privacy Policy:
 * https://www.google.com/policies/privacy
 *
 * @param userID Value to set as the Firebase User ID (or nil to clear it).
 */
+ (void)setUserID:(nullable NSString*)userID;

/**
 * Wrapper for Firebase's setAnalyticsCollectionEnabled method. (For convenience.)
 *
 * Sets whether analytics collection is enabled for this app on this device. This setting is
 * persisted across app sessions. By default it is enabled.
 *
 * @param analyticsCollectionEnabled A flag that enables or disables Analytics collection.
 */
+ (void)setAnalyticsCollectionEnabled:(BOOL)analyticsCollectionEnabled;

/**
 * Wrapper for Firebase's resetAnalyticsData method. (For convenience.)
 *
 * Clears all analytics data for this instance from the device and resets the app instance ID.
 * FIRAnalyticsConfiguration values will be reset to the default values.
 */
+ (void)resetAnalyticsData;

/**
 * Truncates string parameter value to maximum supported length.
 *
 * @param value - String parameter value to evaluate.
 * @return - As much of the string as will fit in an event parameter.
 */
+ (nullable NSString *)truncateParam:(nullable NSString *)value
NS_SWIFT_NAME(trimParam(_:));

/**
 * Truncates user property value to maximum supported length.
 *
 * @param value - User property value to evaluate.
 * @return - As much of the string as will fit in a user property.
 */
+ (nullable NSString *)truncateUserProp:(nullable NSString *)value
NS_SWIFT_NAME(trimUserProp(_:));

// MARK: - Validation/enforcement of Firebase/GA4 rules

/**
 * Controls whether to perform Firebase validation for debug builds. Default is true.
 */
+ (void)setValidateInDebug:(BOOL)enable;

/**
 * Controls whether to perform Firebase validation in production. Default is false. If enabled,
 * only sends custom error events to Firebase, no logging or exceptions.
 */
+ (void)setValidateInProduction:(BOOL)enable;

/**
 * Controls whether custom validation error events are sent to Firebase. Default is false.
 */
+ (void)setSendValidationErrorEvents:(BOOL)enable;

/**
 * Controls whether NSInvalidArgumentException exceptions are thrown for Firebase validation
 * errors in debug builds. Default is false.
 */
+ (void)setThrowOnValidationErrorsInDebug:(BOOL)enable;

/**
 * Controls whether string values in event parameters and user properties are truncated to
 * the maximum lengths allowed before passing to Firebase. Default is false.
 *
 * While "validation" is about awareness of issues, this setting is about "enforcement", to
 * prevent Firebase from dropping parameters and user properties that exceed the allowable
 * lengths. If enabled, it applies regardless of build type or whether validation is enabled.
 *
 * Alternatively, use truncateParam: and truncateUserProp: to trim only those string values
 * that may potentially exceed the max.
 */
+ (void)setTruncateStringValues:(BOOL)enable;

// MARK: - Firebase/GA4 DebugView

/**
 * Forces the app to send Firebase events to the DebugView pane in the Firebase console or GA4 property.
 *
 * Useful for testing builds that are not launched directly from Xcode.
 *
 * Call this method from your AppDelegate's `application:didFinishLaunchingWithOptions:` method before
 * calling `[FIRApp configure]`.
 */
+ (void)setFirebaseLaunchArguments;

// MARK: - Google Analytics (UA) dispatch

/**
 * Sends any queued hits to GA360 (Universal Analytics) when app enters the background.
 *
 * FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
 * library and exposes the GAI methods.
 *
 * Call this method from your AppDelegate's `applicationWillResignActive:` method, or from
 * `sceneWillResignActive:` if using Scenes, before the app actually enters the background.
 * Based on: https://developers.google.com/analytics/devguides/collection/ios/v3/dispatch
 */
+ (void)sendHitsInBackground;

@end

#endif // AAHAnalyticsHelper_h
