import 'package:flutter/services.dart';
import 'dart:typed_data';

class FingerprintService {
  static const platform = MethodChannel('com.finger.get/battery');

  /// 1. Initialize and Power on the device
  static Future<int> openDevice() async {
    try {
      final int result = await platform.invokeMethod('opendev');
      return result; // 0 for success
    } on PlatformException catch (e) {
      print("Error opening device: ${e.message}");
      return -1;
    }
  }

  /// 2. Capture a fingerprint for Enrollment
  /// Returns a Map with 'text' (Base64 string) and 'bytes' (Uint8List image)
  static Future<Map<String, dynamic>?> enroll() async {
    try {
      final Map<dynamic, dynamic>? result = await platform.invokeMethod('enroll');
      if (result != null) {
        return {
          'text': result['text'] as String,
          'bytes': result['bytes'] as Uint8List,
        };
      }
    } catch (e) {
      print("Enrollment failed: $e");
    }
    return null;
  }

  /// 3. Search for a finger against a list of saved templates
  static Future<Map<String, dynamic>?> search(List<String> registeredTemplates) async {
    try {
      final Map<dynamic, dynamic>? result = await platform.invokeMethod('search', {
        'fpcharlist': registeredTemplates,
        'time': 15000, // 15 second timeout
      });
      if (result != null) {
        return {
          'score': result['score'] as int,
          'id': result['id'] as int, // The index of the matched template
          'bytes': result['bytes'] as Uint8List,
        };
      }
    } catch (e) {
      print("Search failed: $e");
    }
    return null;
  }
}