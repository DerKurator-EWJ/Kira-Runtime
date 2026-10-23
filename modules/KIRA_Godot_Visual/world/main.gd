extends Node3D

# Visual prototype: real Godot 3D scene + real Core Bridge event transport.
# It does not generate AI chat responses or modify the relational core.
var status_label: Label
var log_box: RichTextLabel
var input_line: LineEdit
var token_line: LineEdit
var http: HTTPRequest
var pending_action := ""
var pending_text := ""
var bridge_url := "http://127.0.0.1:8765"

func _ready() -> void:
    _build_world()
    _build_ui()
    http = HTTPRequest.new()
    add_child(http)
    http.request_completed.connect(_on_http_completed)
    _log("SYSTEM", "KIRA WORLD 1.7 gestartet. Godot 3D-Szene aktiv.")
    _log("SYSTEM", "Bridge optional: Kira Desktop starten, dort 'Bridge starten' klicken und Token eintragen.")

func _build_world() -> void:
    var environment := WorldEnvironment.new()
    var env := Environment.new()
    env.background_mode = Environment.BG_COLOR
    env.background_color = Color("06120d")
    env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
    env.ambient_light_color = Color("4b9b70")
    env.ambient_light_energy = 0.5
    environment.environment = env
    add_child(environment)
    var light := DirectionalLight3D.new()
    light.rotation_degrees = Vector3(-50, -35, 0)
    light.light_energy = 1.4
    add_child(light)
    _box(Vector3(0, -0.2, 0), Vector3(12, 0.3, 12), Color("14261b"))
    _box(Vector3(0, 1.5, -4), Vector3(10, 3, 0.2), Color("193529"))
    _box(Vector3(-4.5, 1.5, 0), Vector3(0.2, 3, 8), Color("173023"))
    _box(Vector3(0, 0.45, -1.0), Vector3(2.7, 0.15, 1.3), Color("254f38"))
    for x in [-1.1, 1.1]:
        for z in [-1.45, -0.55]:
            _box(Vector3(x, 0.15, z), Vector3(0.13, 0.65, 0.13), Color("365b43"))
    _box(Vector3(2.5, 0.5, -2.3), Vector3(2.2, 1.0, 0.9), Color("254235"))
    _box(Vector3(0, 1.5, -3.86), Vector3(3, 1.6, 0.05), Color("20573e"))
    var camera := Camera3D.new()
    camera.position = Vector3(6, 5, 8)
    camera.look_at(Vector3(0, 0.8, -1.2))
    camera.current = true
    add_child(camera)

func _box(pos: Vector3, size: Vector3, color: Color) -> void:
    var mesh_instance := MeshInstance3D.new()
    var mesh := BoxMesh.new()
    mesh.size = size
    mesh_instance.mesh = mesh
    mesh_instance.position = pos
    var mat := StandardMaterial3D.new()
    mat.albedo_color = color
    mat.metallic = 0.08
    mat.roughness = 0.85
    mesh_instance.material_override = mat
    add_child(mesh_instance)

func _build_ui() -> void:
    var layer := CanvasLayer.new()
    add_child(layer)
    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    layer.add_child(root)
    var panel := PanelContainer.new()
    panel.anchor_left = 0.02
    panel.anchor_top = 0.05
    panel.anchor_right = 0.46
    panel.anchor_bottom = 0.96
    root.add_child(panel)
    var style := StyleBoxFlat.new()
    style.bg_color = Color(0.015, 0.06, 0.035, 0.92)
    style.border_color = Color("35b56e")
    style.set_border_width_all(1)
    style.set_content_margin_all(15)
    panel.add_theme_stylebox_override("panel", style)
    var col := VBoxContainer.new()
    panel.add_child(col)
    var title := Label.new()
    title.text = "KIRA // WORLD 1.7"
    title.add_theme_color_override("font_color", Color("79ffae"))
    title.add_theme_font_size_override("font_size", 24)
    col.add_child(title)
    status_label = Label.new()
    status_label.text = "3D: AKTIV  |  BRIDGE: NICHT VERBUNDEN"
    status_label.add_theme_color_override("font_color", Color("a2d9b5"))
    col.add_child(status_label)
    col.add_child(HSeparator.new())
    log_box = RichTextLabel.new()
    log_box.bbcode_enabled = false
    log_box.size_flags_vertical = Control.SIZE_EXPAND_FILL
    log_box.add_theme_color_override("default_color", Color("a0efb9"))
    col.add_child(log_box)
    var token_label := Label.new()
    token_label.text = "Bridge-Token (lokal, nicht gespeichert):"
    col.add_child(token_label)
    token_line = LineEdit.new()
    token_line.secret = true
    token_line.placeholder_text = "Inhalt aus %LOCALAPPDATA%\\KiraDesktop\\bridge_token.txt"
    col.add_child(token_line)
    var row := HBoxContainer.new()
    col.add_child(row)
    var ping := Button.new()
    ping.text = "Bridge prüfen"
    ping.pressed.connect(_check_bridge)
    row.add_child(ping)
    var reload := Button.new()
    reload.text = "Ereignisse laden"
    reload.pressed.connect(_load_events)
    row.add_child(reload)
    input_line = LineEdit.new()
    input_line.placeholder_text = "Ereignis für Kira eingeben ..."
    input_line.text_submitted.connect(func(_v): _send_event())
    col.add_child(input_line)
    var send := Button.new()
    send.text = "Ereignis an Kira Bridge senden"
    send.pressed.connect(_send_event)
    col.add_child(send)
    var note := Label.new()
    note.text = "Visual Prototype: Ereignisspeicher, noch keine KI-Chatantwort."
    note.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    col.add_child(note)

func _log(who: String, message: String) -> void:
    if log_box != null:
        log_box.append_text("[" + who + "] " + message + "\n\n")

func _headers() -> PackedStringArray:
    return PackedStringArray(["Authorization: Bearer " + token_line.text.strip_edges(), "Content-Type: application/json"])

func _request(path: String, action: String, method: int = HTTPClient.METHOD_GET, body: String = "") -> void:
    if token_line.text.strip_edges().is_empty():
        _log("SYSTEM", "Bitte zuerst das lokale Bridge-Token eintragen.")
        return
    if pending_action != "":
        _log("SYSTEM", "Bitte vorherige Anfrage abwarten.")
        return
    pending_action = action
    var error := http.request(bridge_url + path, _headers(), method, body)
    if error != OK:
        pending_action = ""
        _log("ERROR", "HTTP-Anfrage konnte nicht gestartet werden: " + str(error))

func _check_bridge() -> void:
    _request("/v1/health", "health")

func _load_events() -> void:
    _request("/v1/events", "events")

func _send_event() -> void:
    var value := input_line.text.strip_edges()
    if value.is_empty():
        return
    pending_text = value
    var event := {
        "event_id": _uuid_v4(),
        "timestamp": Time.get_datetime_string_from_system(true),
        "actor_id": "Kurator",
        "kind": "observation",
        "scope": "EPISODIC",
        "payload": {"text": value, "source": "godot_world_1_7"}
    }
    _request("/v1/events", "post", HTTPClient.METHOD_POST, JSON.stringify(event))

func _uuid_v4() -> String:
    var data := Crypto.new().generate_random_bytes(16)
    data[6] = (data[6] & 0x0f) | 0x40
    data[8] = (data[8] & 0x3f) | 0x80
    var hex := data.hex_encode()
    return "%s-%s-%s-%s-%s" % [hex.substr(0,8),hex.substr(8,4),hex.substr(12,4),hex.substr(16,4),hex.substr(20,12)]

func _on_http_completed(result: int, response_code: int, _headers_out: PackedStringArray, body: PackedByteArray) -> void:
    var action := pending_action
    pending_action = ""
    if result != HTTPRequest.RESULT_SUCCESS:
        status_label.text = "3D: AKTIV  |  BRIDGE: OFFLINE"
        _log("ERROR", "Keine Verbindung zum lokalen Bridge-Server. Ist Kira Desktop gestartet?")
        return
    var parsed = JSON.parse_string(body.get_string_from_utf8())
    if response_code < 200 or response_code >= 300:
        _log("ERROR", "HTTP " + str(response_code) + ": " + str(parsed))
        return
    status_label.text = "3D: AKTIV  |  BRIDGE: VERBUNDEN"
    if action == "health":
        _log("BRIDGE", "Verbindung hergestellt: " + str(parsed))
    elif action == "post":
        _log("KURATOR", pending_text)
        _log("BRIDGE", "Ereignis persistent gespeichert.")
        input_line.clear()
    elif action == "events" and parsed is Dictionary:
        _log("BRIDGE", "Letzte gespeicherte Ereignisse:")
        for event in parsed.get("events", []):
            var payload = event.get("payload", {})
            _log(str(event.get("actor_id", "?")), str(payload.get("text", "(ohne Text)")))
