///
/// AnalyticsHelper.swift
///
/// Helper class to assist with common analytics implementation needs, including:
///
/// - Defining event, parameter, and user property constants to prevent typos
/// - Adding standard parameters to every event
/// - Validating names/values before sending to Firebase
/// - Truncating string values to maximum supported lengths before sending to Firebase
/// - Enabling DebugView in builds not launched directly from Xcode
/// - Sending buffered hits when app goes into the background (Universal Analytics)
///
/// This class cannot be instantiated. All methods are static class methods. For convenience,
/// all commonly-used Firebase methods have identically-named methods here.
///
/// Firebase validation/enforcement behavior can be controlled via the following properties:
///
/// - validateInDebug (default: true)
/// - validateInProduction (default: false)
/// - sendValidationErrorEvents (default: false)
/// - throwOnValidationErrorsInDebug (default: false)
/// - truncateStringValues (default: true)
///
/// This code is intended only to illustrate how you might create an analytics helper class.
/// It is not ready for production use as-is.
///
/// The parts of this sample code that deal with Universal Analytics depend on the presence of
/// Google Tag Manager, which imports the Google Analytics library that provides access to the
/// GAI methods. If you are not using GTM, and not sending hits to GA360 (Universal Analytics),
/// you will need to remove code that references GAI.
///
/// @author Chris Hubbard
/// @copyright Copyright (c) 2021 Adswerve. All rights reserved.
///

import Foundation
import Firebase

class AnalyticsHelper {
    
    // MARK: - Constants (and constant types)
    
    /// Event name constants (define all custom event names here).
    struct Event {
        static let screenView = "screen_view"
        static let errorValidation = "error_validation"
    }
    
    /// Event parameter name constants (define all custom parameter names here).
    struct Param {
        static let screenName = "screen_name"
        static let screenClass = "screen_class"
        static let timestamp = "timestamp"
        static let errorMessage = "error_message"
    }
    
    /// User property name constants (define all custom user property names here).
    struct UserProperty {
        static let environment = "environment"
        static let appInstanceId = "app_instance_id"
        static let timezoneOffset = "timezone_offset"
    }
    
    /// Private constructor to prevent instantiation.
    private init() {
    }
    
    // MARK: - Configuration
    
    /// Indicates whether this class has been configured.
    private static var _isConfigured = false
    
    /// Ensures class is configured before use.
    private static func isConfigured() -> Bool {
        if (!_isConfigured) {
            configure()
        }
        return _isConfigured
    }
    
    /// Configures this helper class and provides an opportunity to set initial state and refresh any user properties
    /// that may have changed since the previous launch.
    ///
    /// This method will be called automatically if needed when using the Firebase helper methods below; however,
    /// to set initial state as early as possible in the app's startup process, calling this manually immediately after
    /// calling `FirebaseApp.configure()` is recommended.
    static func configure() {
                        
        // refresh user properties that may have changed since last launch
        Analytics.setUserProperty(timezoneOffset, forName: UserProperty.timezoneOffset)  // example
        Analytics.setUserProperty(Analytics.appInstanceID(), forName: UserProperty.appInstanceId)  // example
        
        // set an "environment" user property to allow GTM to send hits to the desired GA360 property (optional)
        // also allows test data to be filtered out in GA4/Firebase/BigQuery
        // Note: Due to the Firebase SDK's startup sequence, this user property may not be set until
        // after first_open and the first session_start, and potentially other automatic events that
        // occur immediately after the first launch. If this timing presents issues, the alternative
        // in traditional GA (Universal Analytics) is to use a GTM function call variable to supply
        // "environment" for each tag. (Currently there is no workaround for Firebase/GA4/BigQuery.)
        if isDebugBuild {
            // default implementation determines environment based on build type, but
            // the criteria can be made more sophisticated as needed
            Analytics.setUserProperty("test", forName: UserProperty.environment)
        } else {
            Analytics.setUserProperty("production", forName: UserProperty.environment)
        }
        
        // set GA dispatch interval (only applicable if using GTM to send data to Universal Analytics)
        setDispatchInterval()
        
        _isConfigured = true
    }
    
    // MARK: - Firebase helpers
    
    /// Wrapper for Firebase's logEvent method, providing an opportunity to validate the event, enforce Firebase rules,
    /// append additional standard parameters, etc. before passing the event to Firebase.
    ///
    /// - Parameters:
    ///  - name: Name of the event.
    ///  - params: Dictionary of event parameters (optional).
    static func logEvent(_ name: String, parameters: [String: Any]?) {
        if isConfigured() {
            // append additional parameters before logging the event (optional)
            // this could be done on every event, or just on certain events
            var newParams: [String: Any] = parameters ?? [:]
            newParams[Param.timestamp] = timestamp // example: append timestamp parameter
            
            // validate event name and parameters before passing event to Firebase
            validateEvent(name, parameters: newParams)
            
            // log updated event to Firebase Analytics
            Analytics.logEvent(name, parameters: truncateStringValues ? truncateParams(newParams) : newParams)
        } else {
            // pass directly to Firebase
            Analytics.logEvent(name, parameters: parameters)
        }
    }
    
    /// Wrapper for Firebase's setDefaultEventParameters method, providing an opportunity to validate the parameters and
    /// enforce Firebase rules before passing them to Firebase.
    ///
    /// - Parameter parameters: Dictionary of event parameters (or nil to clear them).
    static func setDefaultEventParameters(_ parameters: [String: Any]?) {
        if isConfigured() {
            validateParameters(parameters, source: #function)
            Analytics.setDefaultEventParameters(truncateStringValues ? truncateParams(parameters) : parameters)
        } else {
            // pass directly to Firebase
            Analytics.setDefaultEventParameters(parameters)
        }
    }
    
    /// Wrapper for Firebase's setUserProperty method, providing an opportunity to validate the user property and
    /// enforce Firebase rules before passing it to Firebase.
    ///
    /// - Parameters:
    ///  - name: Name of the user property.
    ///  - value: Value of the user property (passing nil clears the user property).
    static func setUserProperty(_ value: String?, forName name: String) {
        if isConfigured() {
            validateUserProperty(value, forName: name)
            Analytics.setUserProperty(truncateStringValues ? truncateUserProp(value) : value, forName: name)
        } else {
            // pass directly to Firebase
            Analytics.setUserProperty(value, forName: name)
        }
    }
    
    /// Wrapper for Firebase's setUserID method, providing an opportunity to validate the ID value before passing it to Firebase.
    ///
    /// This feature must be used in accordance with Google's privacy policy:
    /// https://www.google.com/policies/privacy
    ///
    /// - Parameter userID: Value to set as the Firebase User ID (or nil to clear it).
    static func setUserID(_ userID: String?) {
        if isConfigured() {
            validateUserID(userID)
            Analytics.setUserID(userID)
        } else {
            // pass directly to Firebase
            Analytics.setUserID(userID)
        }
    }
    
    /// Wrapper for Firebase's setAnalyticsCollectionEnabled method. (For convenience.)
    ///
    /// Sets whether analytics collection is enabled for this app on this device. This setting
    /// is persisted across app sessions. By default it is enabled.
    ///
    /// - Parameter enabled: A flag that enables or disables Analytics collection.
    static func setAnalyticsCollectionEnabled(_ analyticsCollectionEnabled: Bool) {
        Analytics.setAnalyticsCollectionEnabled(analyticsCollectionEnabled)
    }
    
    /// Wrapper for Firebase's resetAnalyticsData method. (For convenience.)
    ///
    /// Clears all analytics data for this app from the device and resets the app instance id.
    static func resetAnalyticsData() {
        Analytics.resetAnalyticsData()
    }
    
    /// Truncates string parameter value to maximum supported length.
    ///
    /// - Parameter value: String parameter value to evaluate.
    /// - Returns: As much of the string as will fit in an event parameter.
    static func truncateParam(_ value: String?) -> String? {
        guard value != nil else {return value}
        return String(value!.prefix(Validation.parameterValueMaxLength))
    }
    
    /// Truncates user property value to maximum supported length.
    ///
    /// - Parameter value: User property value to evaluate.
    /// - Returns: As much of the string as will fit in a user property.
    static func truncateUserProp(_ value: String?) -> String? {
        guard value != nil else {return value}
        return String(value!.prefix(Validation.userPropertyValueMaxLength))
    }
    
    /// Truncates string parameter values to maximum supported length.
    ///
    /// Because this iterates over the entire parameter dictionary, it's more efficient to use the
    /// public truncateParam(_:) method on just those string values that might exceed the max, but
    /// this method can ensure overall compliance if desired.
    ///
    /// - Parameter parameters: Dictionary of event parameters to evaluate.
    /// - Returns: Dictionary with string values shortened (if needed).
    private static func truncateParams(_ parameters: [String: Any]?) -> [String: Any]? {
        guard parameters != nil else {return parameters}
        var newParams = parameters!
        for (name, value) in newParams {
            if let stringValue = value as? String {
                newParams[name] = String(stringValue.prefix(Validation.parameterValueMaxLength))
            }
        }
        return newParams
    }
    
    // MARK: - Validation/enforcement of Firebase rules
    
    /// Firebase rules as defined at https://firebase.google.com/docs/reference/swift/firebaseanalytics/api/reference/Classes/Analytics
    private struct Validation {
        static let eventMaxParameters = 25
        static let eventNameMaxLength = 40
        static let parameterNameMaxLength = 40
        static let parameterValueMaxLength = 100
        static let userPropertyNameMaxLength = 24
        static let userPropertyValueMaxLength = 36
        static let userIdValueMaxLength = 256
        static let eventNamePattern = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        static let parameterNamePattern = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        static let userPropertyNamePattern = "^(?!ga_|google_|firebase_)[A-Za-z]{1}[A-Za-z0-9_]{0,%d}$"
        static let eventNameRegex = makeEventNameRegex()
        static let parameterNameRegex = makeParameterNameRegex()
        static let userPropertyNameRegex = makeUserPropertyNameRegex()
        
        /// Compiles regex pattern for Firebase event name validation using constants above.
        /// - Throws: NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
        private static func makeEventNameRegex() -> NSRegularExpression? {
            let pattern = String(format: eventNamePattern, eventNameMaxLength - 1)
            if let regex = try? NSRegularExpression(pattern: pattern) {
                return regex
            } else if AnalyticsHelper.isDebugBuild {
                NSException(name: NSExceptionName.invalidArgumentException, reason: "Invalid regex pattern for Firebase event name validation", userInfo: nil).raise()
            }
            return nil
        }
        
        /// Compiles regex pattern for Firebase parameter name validation using constants above.
        /// - Throws: NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
        private static func makeParameterNameRegex() -> NSRegularExpression? {
            let pattern = String(format: parameterNamePattern, parameterNameMaxLength - 1)
            if let regex = try? NSRegularExpression(pattern: pattern) {
                return regex
            } else if AnalyticsHelper.isDebugBuild {
                NSException(name: NSExceptionName.invalidArgumentException, reason: "Invalid regex pattern for Firebase parameter name validation", userInfo: nil).raise()
            }
            return nil
        }
        
        /// Compiles regex pattern for Firebase user property name validation using constants above.
        /// - Throws: NSInvalidArgumentException if pattern string is invalid. (Test builds only.)
        private static func makeUserPropertyNameRegex() -> NSRegularExpression? {
            let pattern = String(format: userPropertyNamePattern, userPropertyNameMaxLength - 1)
            if let regex = try? NSRegularExpression(pattern: pattern) {
                return regex
            } else if AnalyticsHelper.isDebugBuild {
                NSException(name: NSExceptionName.invalidArgumentException, reason: "Invalid regex pattern for Firebase user property name validation", userInfo: nil).raise()
            }
            return nil
        }
        
        /// Indicates whether regex compiled successfully.
        static var isRegexReady: Bool {
            return eventNameRegex != nil && parameterNameRegex != nil && userPropertyNameRegex != nil
        }
        
    } // Validation class
    
    /// Controls whether to perform Firebase validation for debug builds. Default is true.
    static var validateInDebug = true
    
    /// Controls whether to perform Firebase validation in production. Default is false. If enabled, only sends custom error events to Firebase, no logging or exceptions.
    static var validateInProduction = false
    
    /// Controls whether Firebase validation errors are logged for debug builds. Default is false.
    static var sendValidationErrorEvents = false
    
    /// Controls whether NSInvalidArgumentException exceptions are thrown for Firebase validation errors in debug builds. Default is false.
    static var throwOnValidationErrorsInDebug = false
    
    /// Controls whether string values in event parameters and user properties are truncated to the maximum lengths allowed before passing to Firebase. Default is true.
    /// While "validation" is about awareness of issues, this setting is about "enforcement", to prevent Firebase from dropping parameters and user properties that exceed
    /// the allowable lengths. If enabled, it applies regardless of build type or whether validation is enabled. Alternatively, use truncateParam(_:) and
    /// truncateUserProp(_:) to trim only those string values that might potentially exceed the max.
    static var truncateStringValues = true

    /// Determines whether validation rules should be applied.
    private static var isValidationEnabled: Bool {
        return ((validateInDebug && isDebugBuild) || (validateInProduction && !isDebugBuild)) && Validation.isRegexReady
    }
    
    /// If validation is enabled, checks the event name, parameter count, and parameter names and values against the Firebase/GA4 rules.
    ///
    /// See: https://firebase.google.com/docs/reference/swift/firebaseanalytics/api/reference/Classes/Analytics#logevent_parameters
    ///
    /// - Parameters:
    ///   - name: Name of the event.
    ///   - parameters: Dictionary of event parameters (optional).
    private static func validateEvent(_ name: String, parameters: [String: Any]?) {
        guard isValidationEnabled else {return}
        // validate event name
        let isInvalidName = Validation.eventNameRegex?.numberOfMatches(in: name, range: NSMakeRange(0, name.count)) != 1
        if isInvalidName {
            let errorMessage = "Invalid event name '\(name)'"
            handleValidationError(errorMessage)
        }
        // validate parameter count
        if parameters != nil {
            let parameterCount = parameters!.count
            let isInvalidCount = parameterCount > Validation.eventMaxParameters
            if isInvalidCount {
                let errorMessage = "Too many parameters in event '\(name)': contains \(parameterCount), max \(Validation.eventMaxParameters)"
                handleValidationError(errorMessage)
            }
        }
        // validate parameters
        validateParameters(parameters, source: name)
    }
    
    /// If validation is enabled, checks each event parameter name and value against the Firebase/GA4 rules.
    ///
    /// See: https://firebase.google.com/docs/reference/swift/firebaseanalytics/api/reference/Classes/Analytics#parameters
    ///
    /// - Parameters:
    ///   - parameters: Dictionary of event parameters (optional).
    ///   - source: Source of the parameters (for error message use).
    private static func validateParameters(_ parameters: [String: Any]?, source: String) {
        guard isValidationEnabled && parameters != nil else {return}
        for (name, value) in parameters! {
            // validate parameter name
            let isInvalidName = Validation.parameterNameRegex?.numberOfMatches(in: name, range: NSMakeRange(0, name.count)) != 1
            if isInvalidName {
                let errorMessage = "Invalid parameter name '\(name)' in '\(source)'"
                handleValidationError(errorMessage)
            }
            // validate parameter value
            if name == "items" {
                // special handling required for Ecommerce "items" parameter, which contains an array of products
                if let productArray = value as? [[String: Any]] {
                    for product in productArray {
                        validateParameters(product, source: "\(source) [items]")
                    }
                }
            } else {
                // normal event parameter
                if let stringValue = value as? String {
                    let isInvalidValue = stringValue.count > Validation.parameterValueMaxLength
                    if isInvalidValue {
                        let errorMessage = "Value too long for parameter '\(name)' in '\(source)': \(stringValue)"
                        handleValidationError(errorMessage)
                    }
                }
            }
        }
    }
    
    /// If validation is enabled, checks the user property name and value against the Firebase/GA4 rules.
    ///
    /// See: https://firebase.google.com/docs/reference/swift/firebaseanalytics/api/reference/Classes/Analytics#setuserproperty_forname
    ///
    /// - Parameters:
    ///   - value: Value of the user property.
    ///   - name: Name of the user property.
    private static func validateUserProperty(_ value: String?, forName name: String) {
        guard isValidationEnabled else {return}
        // validate user property name
        let isInvalidName = Validation.userPropertyNameRegex?.numberOfMatches(in: name, range: NSMakeRange(0, name.count)) != 1
        if isInvalidName {
            let errorMessage = "Invalid user property name '\(name)'"
            handleValidationError(errorMessage)
        }
        // validate user property value
        if value != nil {
            let isInvalidValue = value!.count > Validation.userPropertyNameMaxLength
            if isInvalidValue {
                let errorMessage = "Value too long for user property '\(name)': \(value!)"
                handleValidationError(errorMessage)
            }
        }
    }
    
    /// If validation is enabled, checks the user ID against the Firebase/GA4 rules.
    ///
    /// See: https://firebase.google.com/docs/reference/swift/firebaseanalytics/api/reference/Classes/Analytics#setuserid_
    ///
    /// - Parameter userID: Value of the user ID.
    private static func validateUserID(_ userID: String?) {
        guard isValidationEnabled && userID != nil else {return}
        let isInvalidValue = userID!.count > Validation.userIdValueMaxLength
        if isInvalidValue {
            let errorMessage = "User ID is too long: \(userID!)"
            handleValidationError(errorMessage)
        }
    }
    
    /// Handles the validation error by logging it, sending a custom error event, and/or throwing an NSInvalidArgumentException exception.
    ///
    /// Behavior is controlled via the "validation" configuration properties. (Logging and exceptions are limited to debug builds only.)
    ///
    /// - Parameter errorMessage: The message to be communicated.
    /// - Throws: NSInvalidArgumentException if exception option is enabled. (Test builds only.)
    private static func handleValidationError(_ errorMessage: String) {
        // log error (debug builds only)
        if isDebugBuild {
            print("ERROR: \(errorMessage)")
        }
        // error event option (available in debug and production)
        if sendValidationErrorEvents {
            let truncatedMessage = (errorMessage.count > Validation.parameterValueMaxLength) ? String(errorMessage.prefix(Validation.parameterValueMaxLength - 3) + "...") : errorMessage
            let errorParams = [Param.errorMessage: truncatedMessage]
            Analytics.logEvent(Event.errorValidation, parameters: errorParams)
        }
        // NSInvalidArgumentException option (debug builds only)
        // note that the exception will prevent an error event (see above) from being sent
        if throwOnValidationErrorsInDebug && isDebugBuild {
            NSException(name: NSExceptionName.invalidArgumentException, reason: errorMessage, userInfo: nil).raise()
        }
    }
    
    // MARK: - Firebase/GA4 DebugView
    
    /// Indicates whether events should be sent to DebugView in the Firebase console and GA4 property.
    ///
    /// Default implementation is based on DEBUG flag, but you can customize this to enable DebugView for
    /// select "release" builds that are used for testing. Do NOT enable for true production builds!
    ///
    /// To enable DebugView, call `AnalyticsHelper.setFirebaseLaunchArguments()` before
    /// calling `FirebaseApp.configure()`(see below).
    private static var sendToDebugView: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
    
    /// Forces the app to send Firebase events to the DebugView pane in the Firebase console or GA4 property.
    ///
    /// Useful for testing builds that are not launched directly from Xcode.
    ///
    /// Call this method from your AppDelegate's `application(_:didFinishLaunchingWithOptions:)`
    /// method before calling `FirebaseApp.configure()`.
    static func setFirebaseLaunchArguments() {
        if sendToDebugView {
            var newArguments = ProcessInfo.processInfo.arguments
            newArguments.append("-FIRAnalyticsDebugEnabled")
            newArguments.append("-FIRDebugEnabled")
            newArguments.append("-FIRAnalyticsVerboseLoggingEnabled")
            ProcessInfo.processInfo.setValue(newArguments, forKey: "arguments")
        }
    }
    
    // MARK: - Google Analytics (UA) dispatch
    
    /// Dispatch interval for traditional GA360 (Universal Analytics) hits in production builds.
    ///
    /// FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
    /// library and exposes the GAI methods.
    ///
    /// By default hits are dispatched every 2 minutes when app is in the foreground.
    /// Do not reduce or you risk excessive network activity and battery drain.
    private static let gaProductionDispatchInterval: Double = 120  // seconds
    
    /// Dispatch handler property for sending GA hits in the background
    private static var dispatchHandler: ((_ result: GAIDispatchResult) -> Void)?
    
    /// Sets the dispatch interval for Google Analytics hits sent by Google Tag Manager.
    ///
    /// FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
    /// library and exposes the GAI methods.
    ///
    /// The default GA dispatch interval for production apps is every 2 minutes (120 seconds), but
    /// for debug builds it is helpful to set the interval to 1 second so hits go out immediately.
    private static func setDispatchInterval() {
        if isDebugBuild {
            GAI.sharedInstance().dispatchInterval = 1
            GAI.sharedInstance().logger.logLevel = GAILogLevel.verbose
        } else {
            GAI.sharedInstance().dispatchInterval = gaProductionDispatchInterval
        }
    }
    
    /// Sends any queued hits to GA360 when app enters the background.
    ///
    /// FOR USE WITH GOOGLE TAG MANAGER, which imports the Google Analytics
    /// library and exposes the GAI methods.
    ///
    /// Call this method from your AppDelegate's `applicationWillResignActive(_:)` method, or from
    /// `sceneWillResignActive(_:)` if using Scenes, before the app actually enters the background.
    /// Based on: https://developers.google.com/analytics/devguides/collection/ios/v3/dispatch
    static func sendHitsInBackground () {
        guard isConfigured() == true else {return}
        var taskExpired = false
        let taskId = UIApplication.shared.beginBackgroundTask(withName: "sendHitsInBackground", expirationHandler: {taskExpired = true})
        if (UIBackgroundTaskIdentifier.invalid == taskId) {
            return
        }
        dispatchHandler = { (result) -> Void in
            // Send hits until no hits are left, a dispatch error occurs, or
            // the background task expires
            if (result == GAIDispatchResult.good && !taskExpired) {
                GAI.sharedInstance().dispatch(completionHandler: dispatchHandler)
            } else {
                // restore dispatch interval and end task
                setDispatchInterval()
                UIApplication.shared.endBackgroundTask(taskId)
            }
        }
        GAI.sharedInstance().dispatch(completionHandler: dispatchHandler)
    }
    
    // MARK: - Private convenience properties
    
    /// Indicates whether this is a debug build.
    private static var isDebugBuild: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
    
    /// String representation of current timestamp.
    private static var timestamp: String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS 'GMT'Z '('z')'"  // 2020-02-11 11:26:02.868 GMT-0800 (PST)
        return dateFormatter.string(from: Date())
    }
    
    /// Current timezone offset in hours vs GMT (e.g., -8.0, -7.0, 2.0, 1.0).
    private static var timezoneOffset: String {
        let offsetInHours = Double(TimeZone.current.secondsFromGMT()) / 3600.0
        return String(format: "%1.1f", offsetInHours)
    }
    
} // AnalyticsHelper
