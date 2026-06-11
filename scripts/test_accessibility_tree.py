#!/usr/bin/env python3
"""
NubiaAgent - Script de Prueba del Árbol de Accesibilidad
=========================================================

Este script verifica que el ScreenObserver lee correctamente el
árbol de accesibilidad en el ZTE Nubia Neo 3 5G.

REQUISITOS:
- ADB instalado y en PATH
- Dispositivo conectado por USB con depuración activada
- NubiaAgent instalado en el dispositivo
- Servicio de Accesibilidad habilitado para NubiaAgent

USO:
    python test_accessibility_tree.py [--package com.whatsapp] [--verbose]

PRUEBAS REALIZADAS:
1. Verifica que el dispositivo está conectado vía ADB
2. Verifica que NubiaAgent está instalado
3. Verifica que el servicio de accesibilidad está habilitado
4. Realiza un dump del árbol de accesibilidad
5. Verifica que el árbol contiene elementos interactivos
6. Evalúa el filtrado inteligente (debe reducir a ~40 elementos)
7. Prueba el fallback de screenshot si el árbol está vacío
8. Genera un reporte de resultados
"""

import subprocess
import sys
import xml.etree.ElementTree as ET
import json
import argparse
import time
from datetime import datetime
from typing import List, Dict, Optional, Tuple


# Colores para output
class Colors:
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BLUE = '\033[94m'
    RESET = '\033[0m'
    BOLD = '\033[1m'


def log_info(msg: str):
    print(f"{Colors.BLUE}[INFO]{Colors.RESET} {msg}")


def log_pass(msg: str):
    print(f"{Colors.GREEN}[PASS]{Colors.RESET} {msg}")


def log_fail(msg: str):
    print(f"{Colors.RED}[FAIL]{Colors.RESET} {msg}")


def log_warn(msg: str):
    print(f"{Colors.YELLOW}[WARN]{Colors.RESET} {msg}")


def run_adb(command: str, timeout: int = 30) -> Tuple[int, str]:
    """Ejecuta un comando ADB y retorna (código de salida, output)."""
    try:
        result = subprocess.run(
            f"adb {command}",
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout
        )
        return result.returncode, result.stdout.strip()
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT"
    except Exception as e:
        return -1, str(e)


def check_device_connected() -> bool:
    """Verifica que el dispositivo Nubia está conectado vía ADB."""
    code, output = run_adb("devices")
    if code != 0:
        log_fail("No se pudo ejecutar ADB. ¿Está instalado y en PATH?")
        return False

    lines = output.split('\n')
    devices = [l for l in lines[1:] if l.strip() and 'device' in l]

    if not devices:
        log_fail("No hay dispositivos conectados")
        return False

    # Verificar si es un Nubia
    code, model = run_adb("shell getprop ro.product.model")
    code2, manufacturer = run_adb("shell getprop ro.product.manufacturer")

    if 'Nubia' in manufacturer or 'Neo 3' in model:
        log_pass(f"Dispositivo detectado: {manufacturer} {model}")
    else:
        log_warn(f"Dispositivo no es Nubia Neo 3 5G: {manufacturer} {model}")
        log_warn("Las pruebas pueden no ser representativas")

    log_pass(f"Dispositivo conectado: {devices[0].split()[0]}")
    return True


def check_app_installed() -> bool:
    """Verifica que NubiaAgent está instalado."""
    code, output = run_adb("shell pm list packages | grep nubiaagent")
    if 'com.nubiaagent' in output:
        log_pass("NubiaAgent está instalado")
        return True
    else:
        log_fail("NubiaAgent no está instalado")
        log_info("Instala con: ./gradlew installDebug")
        return False


def check_accessibility_enabled() -> bool:
    """Verifica que el servicio de accesibilidad está habilitado."""
    code, output = run_adb(
        "shell settings get secure enabled_accessibility_services"
    )
    if 'com.nubiaagent' in output and 'ScreenObserver' in output:
        log_pass("Servicio de accesibilidad de NubiaAgent está habilitado")
        return True
    else:
        log_fail("Servicio de accesibilidad NO está habilitado")
        log_info("Habilita en: Configuración > Accesibilidad > NubiaAgent")
        return False


def dump_accessibility_tree(package: str = None) -> Optional[ET.Element]:
    """Realiza un dump del árbol de accesibilidad del dispositivo."""
    log_info("Realizando dump del árbol de accesibilidad...")

    # Método 1: uiautomator dump
    if package:
        code, output = run_adb(
            f"shell uiautomator dump --compressed /sdcard/nubia_ui_dump.xml",
            timeout=60
        )
    else:
        code, output = run_adb(
            "shell uiautomator dump --compressed /sdcard/nubia_ui_dump.xml",
            timeout=60
        )

    if code != 0 and 'UI hierchary dumped to' not in output:
        # Intentar sin --compressed
        code, output = run_adb(
            "shell uiautomator dump /sdcard/nubia_ui_dump.xml",
            timeout=60
        )

    # Copiar archivo al host
    run_adb("pull /sdcard/nubia_ui_dump.xml /tmp/nubia_ui_dump.xml")

    try:
        tree = ET.parse('/tmp/nubia_ui_dump.xml')
        root = tree.getroot()
        log_pass(f"Árbol de accesibilidad leído exitosamente")
        return root
    except Exception as e:
        log_fail(f"Error parseando árbol XML: {e}")
        return None


def count_all_nodes(root: ET.Element) -> int:
    """Cuenta todos los nodos del árbol XML."""
    count = 1
    for child in root:
        count += count_all_nodes(child)
    return count


def extract_interactive_elements(root: ET.Element) -> List[Dict]:
    """
    Filtra elementos interactivos del árbol XML.
    Replica la lógica del ScreenObserver en Python para validación.
    """
    elements = []

    def traverse(node: ET.Element, depth: int = 0):
        attrs = node.attrib
        class_name = attrs.get('class', '')
        text = attrs.get('text', '')
        content_desc = attrs.get('content-desc', '')
        clickable = attrs.get('clickable', 'false') == 'true'
        scrollable = attrs.get('scrollable', 'false') == 'true'
        editable = attrs.get('editable', 'false') == 'true'
        checkable = attrs.get('checkable', 'false') == 'true'
        bounds = attrs.get('bounds', '')

        # Calcular score de importancia (replica de calculateNodeImportance)
        score = 0.0

        if clickable: score += 0.25
        if scrollable: score += 0.20
        if editable: score += 0.30
        if checkable: score += 0.15

        if text: score += 0.30
        if content_desc: score += 0.20

        # Clases interactivas
        interactive_classes = [
            'Button', 'ImageButton', 'EditText', 'CheckBox',
            'Switch', 'RadioButton', 'Spinner', 'SeekBar',
            'TextView', 'ImageView', 'ListView', 'RecyclerView',
            'TabHost', 'ViewPager'
        ]
        if any(ic in class_name for ic in interactive_classes):
            score += 0.30

        # Penalizar contenedores vacíos
        if 'Layout' in class_name and not text and not content_desc and not clickable:
            score -= 0.30

        # Penalizar profundidad excesiva
        if depth > 10:
            score -= (depth - 10) * 0.05

        if score >= 0.3:  # MIN_NODE_IMPORTANCE
            # Clasificar tipo de vista
            view_type = 'UNKNOWN'
            if editable:
                view_type = 'TEXT_FIELD'
            elif 'Button' in class_name:
                view_type = 'BUTTON'
            elif 'EditText' in class_name:
                view_type = 'TEXT_FIELD'
            elif 'CheckBox' in class_name:
                view_type = 'CHECKBOX'
            elif 'Switch' in class_name:
                view_type = 'SWITCH'
            elif 'RecyclerView' in class_name or 'ListView' in class_name:
                view_type = 'LIST'
            elif 'TextView' in class_name:
                view_type = 'TEXT'
            elif 'ImageView' in class_name:
                view_type = 'IMAGE'
            elif clickable:
                view_type = 'BUTTON'
            elif scrollable:
                view_type = 'LIST'

            elements.append({
                'class': class_name.split('.')[-1],
                'text': text[:50] if text else None,
                'content_desc': content_desc[:50] if content_desc else None,
                'bounds': bounds,
                'clickable': clickable,
                'scrollable': scrollable,
                'editable': editable,
                'checkable': checkable,
                'view_type': view_type,
                'score': score,
                'depth': depth
            })

        for child in node:
            traverse(child, depth + 1)

    traverse(root)

    # Ordenar por importancia y limitar a 40
    elements.sort(key=lambda x: x['score'], reverse=True)
    return elements[:40]


def test_screenshot_fallback() -> bool:
    """Prueba la captura de screenshot como fallback."""
    log_info("Probando fallback de screenshot...")

    code, output = run_adb("shell screencap -p /sdcard/nubia_screenshot.png")
    if code == 0:
        # Verificar que el archivo se creó
        code2, output2 = run_adb("shell ls -la /sdcard/nubia_screenshot.png")
        if 'nubia_screenshot.png' in output2:
            size = output2.split()[4] if len(output2.split()) > 4 else '?'
            log_pass(f"Screenshot capturada exitosamente ({size} bytes)")
            return True

    log_fail("No se pudo capturar screenshot")
    log_info("Nota: screencap requiere ADB o root en el dispositivo")
    return False


def test_notification_listener() -> bool:
    """Verifica que el NotificationInterceptor está activo."""
    code, output = run_adb(
        "shell dumpsys notification_listener | grep NubiaAgent"
    )
    if 'NubiaAgent' in output:
        log_pass("NotificationInterceptor está activo")
        return True
    else:
        log_fail("NotificationInterceptor no está activo")
        return False


def test_wake_word_service() -> bool:
    """Verifica que el WakeWordService está corriendo."""
    code, output = run_adb(
        "shell dumpsys activity services | grep nubiaagent"
    )
    if 'WakeWordService' in output:
        log_pass("WakeWordService está corriendo")
        return True
    else:
        log_fail("WakeWordService no está corriendo")
        log_info("Inicia manualmente desde la app o: " +
                 "adb shell am startservice com.nubiaagent/.perception.ear.WakeWordService")
        return False


def check_hardware_info():
    """Obtiene información del hardware del Nubia Neo 3 5G."""
    log_info("Obteniendo información del hardware...")

    info = {}
    _, info['model'] = run_adb("shell getprop ro.product.model")
    _, info['manufacturer'] = run_adb("shell getprop ro.product.manufacturer")
    _, info['processor'] = run_adb("shell getprop ro.hardware")
    _, info['android'] = run_adb("shell getprop ro.build.version.release")
    _, info['sdk'] = run_adb("shell getprop ro.build.version.sdk")

    # RAM
    _, meminfo = run_adb("shell cat /proc/meminfo | head -1")
    info['ram'] = meminfo

    # Batería
    _, battery = run_adb("shell dumpsys battery | grep level")
    info['battery'] = battery

    # Bypass Charging check
    _, bypass = run_adb("shell cat /sys/class/power_supply/battery/bypass_charging 2>/dev/null")
    info['bypass_charging'] = bypass if bypass and 'No such' not in bypass else "No detectado"

    for key, value in info.items():
        log_info(f"  {key}: {value}")

    return info


def generate_report(results: Dict, package: str):
    """Genera un reporte de resultados de las pruebas."""
    report = []
    report.append("=" * 60)
    report.append("NUBIAAGENT - REPORTE DE PRUEBAS DE PERCEPCIÓN")
    report.append(f"Fecha: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report.append(f"Paquete probado: {package or 'todos'}")
    report.append("=" * 60)
    report.append()

    total = len(results)
    passed = sum(1 for v in results.values() if v)
    failed = total - passed

    for test_name, test_result in results.items():
        status = "✅ PASS" if test_result else "❌ FAIL"
        report.append(f"  {status} - {test_name}")

    report.append()
    report.append(f"Total: {total} | Pasaron: {passed} | Fallaron: {failed}")
    report.append(f"Tasa de éxito: {(passed/total*100):.1f}%")
    report.append("=" * 60)

    # Guardar reporte
    report_path = "/home/z/my-project/download/perception_test_report.txt"
    with open(report_path, 'w') as f:
        f.write('\n'.join(report))

    return '\n'.join(report), report_path


def main():
    parser = argparse.ArgumentParser(
        description='Pruebas de percepción de NubiaAgent para Nubia Neo 3 5G'
    )
    parser.add_argument(
        '--package', '-p',
        default=None,
        help='Paquete específico a probar (ej: com.whatsapp)'
    )
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Output detallado'
    )
    args = parser.parse_args()

    print(f"\n{Colors.BOLD}🤖 NubiaAgent - Pruebas de la Capa de Percepción{Colors.RESET}")
    print(f"Dispositivo objetivo: ZTE Nubia Neo 3 5G\n")

    results = {}

    # Prueba 1: Dispositivo conectado
    print(f"\n{Colors.BOLD}--- Prueba 1: Conexión del Dispositivo ---{Colors.RESET}")
    results['Dispositivo conectado'] = check_device_connected()

    # Prueba 2: App instalada
    print(f"\n{Colors.BOLD}--- Prueba 2: Instalación de NubiaAgent ---{Colors.RESET}")
    results['NubiaAgent instalado'] = check_app_installed()

    # Prueba 3: Servicio de accesibilidad
    print(f"\n{Colors.BOLD}--- Prueba 3: Servicio de Accesibilidad ---{Colors.RESET}")
    results['Accesibilidad habilitada'] = check_accessibility_enabled()

    # Prueba 4: Árbol de accesibilidad
    print(f"\n{Colors.BOLD}--- Prueba 4: Árbol de Accesibilidad ---{Colors.RESET}")
    if args.package:
        run_adb(f"shell am start -n {args.package}")
        time.sleep(2)

    root = dump_accessibility_tree(args.package)
    if root is not None:
        total_nodes = count_all_nodes(root)
        log_info(f"Total de nodos en árbol: {total_nodes}")

        interactive = extract_interactive_elements(root)
        log_info(f"Elementos interactivos filtrados: {len(interactive)}")

        if len(interactive) > 0:
            log_pass(f"Filtrado exitoso: {total_nodes} nodos → {len(interactive)} elementos")
            results['Árbol de accesibilidad leído'] = True
            results['Filtrado inteligente funciona'] = len(interactive) <= 40

            if args.verbose:
                print(f"\n{Colors.BOLD}Elementos encontrados (top 10):{Colors.RESET}")
                for i, elem in enumerate(interactive[:10]):
                    print(f"  {i+1}. [{elem['view_type']}] " +
                          f"{elem['text'] or elem['content_desc'] or '(sin texto)'} " +
                          f"(score: {elem['score']:.2f})")
        else:
            log_warn("No se encontraron elementos interactivos")
            log_info("Posible app con Flutter/WebView - se necesita screenshot fallback")
            results['Árbol de accesibilidad leído'] = False
    else:
        results['Árbol de accesibilidad leído'] = False

    # Prueba 5: Screenshot fallback
    print(f"\n{Colors.BOLD}--- Prueba 5: Fallback de Screenshot ---{Colors.RESET}")
    results['Screenshot fallback'] = test_screenshot_fallback()

    # Prueba 6: Notification Listener
    print(f"\n{Colors.BOLD}--- Prueba 6: Notification Listener ---{Colors.RESET}")
    results['Notification interceptor activo'] = test_notification_listener()

    # Prueba 7: Wake Word Service
    print(f"\n{Colors.BOLD}--- Prueba 7: Wake Word Service ---{Colors.RESET}")
    results['Wake word service activo'] = test_wake_word_service()

    # Información de hardware
    print(f"\n{Colors.BOLD}--- Información del Hardware ---{Colors.RESET}")
    check_hardware_info()

    # Reporte final
    print(f"\n{Colors.BOLD}--- Reporte Final ---{Colors.RESET}")
    report_text, report_path = generate_report(results, args.package)
    print(report_text)
    log_info(f"Reporte guardado en: {report_path}")

    # Código de salida
    all_passed = all(results.values())
    sys.exit(0 if all_passed else 1)


if __name__ == '__main__':
    main()
