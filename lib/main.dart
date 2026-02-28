import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const FingerprintApp());
}

class FingerprintApp extends StatelessWidget {
  const FingerprintApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true, 
        colorSchemeSeed: Colors.deepPurple,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  // Must match the name in your MainActivity.kt
  static const platform = MethodChannel('com.finger.get/battery');

  // --- DATA STORAGE ---
  final List<String> _enrolledTemplates = []; 
  Uint8List? _lastCapturedImage;
  String _status = "Initialize the device first";
  bool _isProcessing = false;

  // 1. Initialize the Fingerprint Hardware
  Future<void> _initDevice() async {
    setState(() => _isProcessing = true);
    try {
      final int result = await platform.invokeMethod('opendev');
      setState(() {
        _status = (result == 0) ? "Scanner Online" : "Init Failed: $result";
      });
    } on PlatformException catch (e) {
      setState(() => _status = "Hardware Error: ${e.message}");
    } finally {
      setState(() => _isProcessing = false);
    }
  }

  // 2. Enroll: Capture finger and add it to our List
  Future<void> _enrollFinger() async {
    setState(() {
      _isProcessing = true;
      _status = "Scanning... Place finger on sensor";
    });

    try {
      final Map<dynamic, dynamic>? result = await platform.invokeMethod('enroll');
      if (result != null) {
        final String template = result['text'];
        final Uint8List image = result['bytes'];

        setState(() {
          _enrolledTemplates.add(template); 
          _lastCapturedImage = image;
          _status = "Enroll Success! Count: ${_enrolledTemplates.length}";
        });
      }
    } on PlatformException catch (e) {
      setState(() => _status = "Enroll Error: ${e.message}");
    } finally {
      setState(() => _isProcessing = false);
    }
  }

  // 3. Search: Compare live finger against the entire List
  Future<void> _searchFinger() async {
    if (_enrolledTemplates.isEmpty) {
      setState(() => _status = "Enroll a finger first!");
      return;
    }

    setState(() {
      _isProcessing = true;
      _status = "Identify... Place finger on sensor";
    });

    try {
      // Sending the whole list to the Kotlin background loop
      final Map<dynamic, dynamic>? result = await platform.invokeMethod('search', {
        'fpcharlist': _enrolledTemplates,
        'time': 10000, 
      });

      if (result != null) {
        final int matchIndex = result['id']; 
        final int score = result['score'];
        _lastCapturedImage = result['bytes'];

        setState(() {
          if (matchIndex != -1) {
            _status = "Match Found! ID: $matchIndex (Score: $score)";
          } else {
            _status = "No Match Found in Database";
          }
        });
      }
    } on PlatformException catch (e) {
      setState(() => _status = "Search Error: ${e.message}");
    } finally {
      setState(() => _isProcessing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Fingerprintsy Terminal"),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            // Image Preview (Raw Grayscale Image)
            Container(
              width: 180,
              height: 220,
              decoration: BoxDecoration(
                border: Border.all(color: Colors.deepPurple, width: 2),
                color: Colors.black12,
                borderRadius: BorderRadius.circular(8),
              ),
              child: _lastCapturedImage != null 
                  ? ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: Image.memory(_lastCapturedImage!, fit: BoxFit.cover),
                    )
                  : const Icon(Icons.fingerprint, size: 100, color: Colors.grey),
            ),
            
            const SizedBox(height: 16),
            Text(_status, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            if (_isProcessing) const LinearProgressIndicator(),
            
            const Divider(height: 40),

            // Main Controls
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  onPressed: _isProcessing ? null : _initDevice,
                  icon: const Icon(Icons.usb),
                  label: const Text("Init"),
                ),
                ElevatedButton.icon(
                  onPressed: _isProcessing ? null : _enrollFinger,
                  icon: const Icon(Icons.add),
                  label: const Text("Enroll"),
                ),
                ElevatedButton.icon(
                  onPressed: _isProcessing ? null : _searchFinger,
                  icon: const Icon(Icons.search),
                  label: const Text("Search"),
                ),
              ],
            ),

            const SizedBox(height: 24),
            const Text("Enrolled Database", style: TextStyle(fontWeight: FontWeight.w200)),

            // List of Enrolled Fingers
            Expanded(
              child: ListView.builder(
                itemCount: _enrolledTemplates.length,
                itemBuilder: (context, index) {
                  final String raw = _enrolledTemplates[index];
                  // Using string interpolation for the preview string
                  final String preview = "${raw.substring(0, 25)}...";
                  
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(child: Text("$index")),
                      title: Text("User Finger $index"),
                      subtitle: Text(preview, style: const TextStyle(fontSize: 10)),
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline, color: Colors.red),
                        onPressed: () => setState(() => _enrolledTemplates.removeAt(index)),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}