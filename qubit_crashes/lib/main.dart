import 'dart:async';
import 'dart:io';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:dropdown_search/dropdown_search.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:installed_apps/app_info.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

final ValueNotifier<ThemeMode> themeNotifier = ValueNotifier(ThemeMode.dark);

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const QubitApp());
}

class QubitApp extends StatelessWidget {
  const QubitApp({super.key});
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeNotifier,
      builder: (_, mode, __) {
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          themeMode: mode,
          theme: ThemeData(useMaterial3: true, brightness: Brightness.light, colorSchemeSeed: Colors.deepPurple),
          darkTheme: ThemeData(useMaterial3: true, brightness: Brightness.dark, colorSchemeSeed: Colors.deepPurple, scaffoldBackgroundColor: const Color(0xFF0F0F1E)),
          home: const CrashLogsPage(),
        );
      },
    );
  }
}

class CrashLogsPage extends StatefulWidget {
  const CrashLogsPage({super.key});
  @override
  State<CrashLogsPage> createState() => _CrashLogsPageState();
}

class _CrashLogsPageState extends State<CrashLogsPage> {
  static const _methodChannel = MethodChannel('com.qubit/usage_stats');
  static const _eventChannel = EventChannel('com.qubit/crash_events');
  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();

  List<AppInfo> installedApps = [];
  String? selectedPackage;
  bool isTracking = false;
  bool hasPermission = false, hasNotifListenerPerm = false, hasBatteryPerm = false, isInPip = false;
  int pipCrashCount = 0;
  String debugLog = "Waiting for logs...";
  List<Map<String, dynamic>> liveLogs = [];
  StreamSubscription? _crashStream;
  Timer? _debugTimer;
  bool _readyToSave = false;

  @override
  void initState() {
    super.initState();
    _loadAllDataSequentially();
    _requestNotifPermission();
    _debugTimer = Timer.periodic(const Duration(seconds: 2), (_) => _loadDebugLogs());
  }

  Future<void> _loadAllDataSequentially() async {
    await _loadStoredLogs(); // Load history first
    await _loadApps();       // Load apps list
    await _init();           // Setup native channel
    setState(() => _readyToSave = true);
  }

  Future<void> _init() async {
    _methodChannel.setMethodCallHandler((call) async {
      if (call.method == 'onPipChanged') {
        bool newInPip = call.arguments as bool;
        setState(() { isInPip = newInPip; if (!isInPip) pipCrashCount = 0; });
      }
    });
    await _checkPermission();
    final String? savedPkg = await _methodChannel.invokeMethod('getActiveTarget');
    if (savedPkg != null && savedPkg.isNotEmpty) {
      setState(() { selectedPackage = savedPkg; isTracking = true; });
      _startTracking();
    }
  }

  // ── Permanent Storage ──────────────────────────────────────────────────────
  Future<File> _getLogFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/qubit_v4_history.json');
  }

  Future<void> _loadStoredLogs() async {
    try {
      final file = await _getLogFile();
      if (await file.exists()) {
        final content = await file.readAsString();
        if (content.isNotEmpty) {
          final List<dynamic> json = jsonDecode(content);
          setState(() {
            liveLogs = json.map((e) => Map<String, dynamic>.from(e)).toList();
          });
        }
      }
    } catch (e) { print("Load Error: $e"); }
  }

  Future<void> _saveLogs() async {
    if (!_readyToSave) return;
    try {
      final file = await _getLogFile();
      await file.writeAsString(jsonEncode(liveLogs));
    } catch (e) { print("Save Error: $e"); }
  }

  Future<void> _clearAllData() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete All Data?'),
        content: const Text('This will wipe your entire crash history forever.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('CANCEL')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, true), style: ElevatedButton.styleFrom(backgroundColor: Colors.red), child: const Text('DELETE HISTORY')),
        ],
      ),
    );

    if (confirmed == true) {
      setState(() => liveLogs.clear());
      final file = await _getLogFile();
      if (await file.exists()) await file.delete();
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────
  Future<void> _checkPermission() async {
    final g = await _methodChannel.invokeMethod('hasPermission');
    final n = await _methodChannel.invokeMethod('hasNotificationPerm');
    final b = await _methodChannel.invokeMethod('isIgnoringBattery');
    setState(() { hasPermission = g; hasNotifListenerPerm = n; hasBatteryPerm = b; });
  }

  Future<void> _loadDebugLogs() async {
    final String log = await _methodChannel.invokeMethod('getDebugLog') ?? "";
    if (log != debugLog) setState(() => debugLog = log);
  }

  Future<void> _requestNotifPermission() async {
    await flutterLocalNotificationsPlugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()?.requestNotificationsPermission();
    await flutterLocalNotificationsPlugin.initialize(const InitializationSettings(android: AndroidInitializationSettings('@mipmap/ic_launcher')));
  }

  Future<void> _loadApps() async {
    final apps = await InstalledApps.getInstalledApps(true, true);
    setState(() => installedApps = apps..sort((a, b) => (a.name ?? '').compareTo(b.name ?? '')));
  }

  void _toggleTracking() => isTracking ? _stopTracking() : _startTracking();

  Future<void> _startTracking() async {
    if (selectedPackage == null) return;
    if (!hasPermission) { _showPermissionDialog(type: 'usage'); return; }
    setState(() { isTracking = true; });
    _crashStream?.cancel();
    _crashStream = _eventChannel.receiveBroadcastStream().listen((event) {
      if (mounted) {
        setState(() {
          liveLogs.insert(0, Map<String, dynamic>.from(event));
          if (isInPip) pipCrashCount++;
        });
        _saveLogs();
      }
    });
    await _methodChannel.invokeMethod('startWatching', {'package': selectedPackage});
  }

  Future<void> _stopTracking() async {
    await _methodChannel.invokeMethod('stopWatching');
    _crashStream?.cancel();
    _crashStream = null;
    setState(() => isTracking = false);
  }

  void _showPermissionDialog({required String type}) {
    String t = '', d = '', m = '';
    if (type == 'usage') { t = 'Usage Access'; d = 'Find Qubit and toggle ON.'; m = 'openPermissionSettings'; }
    else if (type == 'notif') { t = 'Notif Access'; d = 'Allow Qubit to read notifications.'; m = 'openNotificationPerm'; }
    else if (type == 'battery') { t = 'No Restrictions'; d = 'Set battery to "No Restrictions".'; m = 'openBatterySettings'; }
    else if (type == 'autostart') { t = 'Xiaomi Autostart'; d = 'Enable Autostart for Qubit.'; m = 'openXiaomiAutostart'; }
    else if (type == 'popup') { t = 'Background Popups'; d = 'Allow Qubit to show popups.'; m = 'openXiaomiDisplayPopups'; }

    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: Text(t), content: Text(d),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        ElevatedButton(onPressed: () async {
          Navigator.pop(ctx);
          await _methodChannel.invokeMethod(m);
          await Future.delayed(const Duration(seconds: 2));
          _checkPermission();
        }, child: const Text('OPEN')),
      ],
    ));
  }

  Widget _buildPermBanner(String msg, String type, {bool isWarning = true}) {
    return Container(
      width: double.infinity, margin: const EdgeInsets.only(bottom: 8), padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: isWarning ? Colors.orange[900]?.withOpacity(0.8) : Colors.blueGrey[900], borderRadius: BorderRadius.circular(10)),
      child: Row(children: [
        Icon(isWarning ? Icons.warning_amber : Icons.info_outline, color: Colors.white, size: 18),
        const SizedBox(width: 8),
        Expanded(child: Text(msg, style: const TextStyle(color: Colors.white, fontSize: 12))),
        TextButton(onPressed: () => _showPermissionDialog(type: type), child: const Text('FIX', style: TextStyle(color: Colors.yellowAccent, fontWeight: FontWeight.bold))),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (isInPip) {
      return Scaffold(
        backgroundColor: Colors.transparent,
        body: Center(
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              // THE PERFECT BUBBLE (Circular, no square background)
              ClipOval(
                child: Container(
                  width: 65, height: 65,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [Colors.deepPurple[800]!, Colors.deepPurple[400]!]),
                    shape: BoxShape.circle,
                  ),
                  child: const Center(child: Icon(Icons.bug_report, size: 35, color: Colors.amberAccent)),
                ),
              ),
              // THE JEWEL (Now on the Top-Left / Opposite)
              if (pipCrashCount > 0)
                Positioned(
                  top: -2,
                  left: -2,
                  child: Container(
                    padding: const EdgeInsets.all(4),
                    decoration: BoxDecoration(color: Colors.red, shape: BoxShape.circle, border: Border.all(color: Colors.white, width: 2), boxShadow: [BoxShadow(color: Colors.black45, blurRadius: 4)]),
                    constraints: const BoxConstraints(minWidth: 26, minHeight: 26),
                    child: Center(child: Text('$pipCrashCount', style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold))),
                  ),
                ),
            ],
          ),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text('Qubit Tracker'), centerTitle: true,
        actions: [
          if (liveLogs.isNotEmpty) IconButton(icon: const Icon(Icons.delete_forever, color: Colors.redAccent, size: 28), onPressed: _clearAllData),
        ],
      ),
      body: CustomScrollView(
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(children: [
                if (!hasPermission) _buildPermBanner('Usage Access OFF', 'usage'),
                if (!hasNotifListenerPerm) _buildPermBanner('Notif Access OFF', 'notif'),
                if (!hasBatteryPerm) _buildPermBanner('Battery: NO RESTRICTIONS', 'battery'),
                _buildPermBanner('Xiaomi: Enable Autostart', 'autostart', isWarning: false),
                _buildPermBanner('Xiaomi: Allow Popups', 'popup', isWarning: false),
                const SizedBox(height: 12),
                DropdownSearch<String>(
                  enabled: !isTracking, items: installedApps.map((e) => e.packageName ?? '').toList(), selectedItem: selectedPackage,
                  onChanged: (v) => setState(() => selectedPackage = v),
                  dropdownDecoratorProps: const DropDownDecoratorProps(dropdownSearchDecoration: InputDecoration(labelText: 'Target App', border: OutlineInputBorder())),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  style: ElevatedButton.styleFrom(minimumSize: const Size(double.infinity, 50), backgroundColor: isTracking ? Colors.redAccent : Colors.tealAccent[400]),
                  onPressed: selectedPackage == null ? null : _toggleTracking,
                  child: Text(isTracking ? 'STOP TRACKING' : 'START TRACKING', style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.black)),
                ),
                const Divider(height: 32),
                ExpansionTile(
                  title: const Text('Internal Service Log', style: TextStyle(fontSize: 12, color: Colors.white54)),
                  children: [Container(width: double.infinity, height: 120, padding: const EdgeInsets.all(8), color: Colors.black, child: SingleChildScrollView(child: Text(debugLog, style: const TextStyle(color: Colors.greenAccent, fontSize: 10, fontFamily: 'monospace'))))],
                ),
                const Align(alignment: Alignment.centerLeft, child: Text('Live Detections', style: TextStyle(fontWeight: FontWeight.bold))),
              ]),
            ),
          ),
          _buildLogListSliver(),
        ],
      ),
    );
  }

  Widget _buildLogListSliver() {
    if (liveLogs.isEmpty) return const SliverFillRemaining(hasScrollBody: false, child: Center(child: Text('No crashes detected', style: TextStyle(color: Colors.white24))));
    return SliverList(
      delegate: SliverChildBuilderDelegate(
        (ctx, i) {
          final log = liveLogs[i];
          final isAnr = (log['type'] ?? '').contains('ANR');
          return Card(
            margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: ListTile(
              leading: Icon(isAnr ? Icons.timer_off : Icons.bug_report, color: isAnr ? Colors.orange : Colors.red),
              title: Text('${log['type']} • ${log['time']}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
              subtitle: const Text('Tap to view details', style: TextStyle(color: Colors.amberAccent, fontSize: 11)),
              trailing: const Icon(Icons.chevron_right, size: 16),
              onTap: () => _showDetailsPage(log),
            ),
          );
        },
        childCount: liveLogs.length,
      ),
    );
  }

  void _showDetailsPage(Map<String, dynamic> log) {
    Navigator.push(context, MaterialPageRoute(builder: (ctx) => Scaffold(
      appBar: AppBar(title: Text('${log['type']} Details')),
      body: SingleChildScrollView(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(width: double.infinity, padding: const EdgeInsets.all(20), color: Colors.deepPurple.withOpacity(0.1), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [_detailRow('Time', log['time'] ?? 'N/A'), _detailRow('Type', log['type'] ?? 'CRASH'), _detailRow('Target', log['package'] ?? 'N/A'), _detailRow('Source', log['source'] ?? 'System')])),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('MESSAGE', style: TextStyle(fontWeight: FontWeight.bold, color: Colors.amber, fontSize: 12)),
              const SizedBox(height: 8), Text(log['shortMsg'] ?? 'No message available', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
              const SizedBox(height: 24), const Text('EXTENDED LOGS / STACK TRACE', style: TextStyle(fontWeight: FontWeight.bold, color: Colors.amber, fontSize: 12)),
              const SizedBox(height: 10),
              Container(height: 250, width: double.infinity, padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: Colors.black, borderRadius: BorderRadius.circular(8)), child: SingleChildScrollView(child: SelectableText(("${log['longMsg'] ?? ''}\n\n${log['stackTrace'] ?? 'No technical stack trace.'}"), style: const TextStyle(color: Colors.greenAccent, fontSize: 11, fontFamily: 'monospace')))),
              const SizedBox(height: 30),
              ElevatedButton.icon(onPressed: () => _shareCrash(log), icon: const Icon(Icons.share), label: const Text('SHARE FULL REPORT'), style: ElevatedButton.styleFrom(minimumSize: const Size(double.infinity, 55), backgroundColor: Colors.deepPurple[700], foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)))),
            ]),
          ),
        ]),
      ),
    )));
  }

  Widget _detailRow(String l, String v) => Padding(padding: const EdgeInsets.only(bottom: 4), child: Row(children: [Text('$l: ', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12, color: Colors.white54)), Expanded(child: Text(v, style: const TextStyle(fontSize: 12, color: Colors.white)))]));

  Future<void> _shareCrash(Map<String, dynamic> d) async {
    final report = 'QUBIT CRASH\nTime: ${d['time']}\nType: ${d['type']}\nPackage: ${d['package']}\nMsg: ${d['shortMsg']}\n\nLOGS:\n${d['longMsg'] ?? ''}\n${d['stackTrace'] ?? ''}';
    await Share.share(report, subject: 'Qubit Crash — ${d['package']}');
  }

  @override
  void dispose() { _debugTimer?.cancel(); super.dispose(); }
}
