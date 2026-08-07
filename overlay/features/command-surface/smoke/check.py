"""Live checks for the additive workspace command surface."""

import os
import urllib.parse
import zipfile

FIXTURE = """\
package smoke.commands

class CommandTarget {
    fun execute() = Unit
}
"""


def _write_jar(uri):
    """A jar in the smoke workspace, for the package-tree listing.

    Built here rather than added to the shared workspace model because listing reads entry *names*
    only -- so a jar of empty entries exercises the command exactly as a real dependency would,
    without putting an unresolvable library on every other feature's classpath.
    """
    workspace = os.path.dirname(urllib.parse.urlparse(uri).path)
    jar = os.path.join(workspace, "command_surface_listing.jar")
    with zipfile.ZipFile(jar, "w") as archive:
        for entry in ("sample/api/Client.class", "sample/api/Client$Builder.class",
                      "sample/api/Codec.class", "sample/api/internal/Buffer.class",
                      "META-INF/MANIFEST.MF"):
            archive.writestr(entry, b"\0")
    return jar


def check(lsp, uri):
    doctor = {}
    for _ in lsp.poll(120):
        doctor = lsp.request("workspace/executeCommand", {
            "command": "kotlin-lsp.doctor", "arguments": []})
        if doctor.get("modules"):
            break
    if not doctor.get("modules"):
        raise AssertionError("doctor returned no imported modules: %r" % doctor)

    locations = lsp.request("workspace/executeCommand", {
        "command": "kotlin-lsp.analyzeStackTrace",
        "arguments": ["java.lang.IllegalStateException\n"
                      "    at smoke.commands.CommandTarget.execute(command_surface.kt:5)"],
    })
    if not locations or locations[0].get("uri") != uri:
        raise AssertionError("stack trace locations = %r, expected %s" % (locations, uri))

    offset = FIXTURE.index("execute") + 1
    fqn = lsp.request("workspace/executeCommand", {
        "command": "kotlin-lsp.copyFullyQualifiedName", "arguments": [uri, offset]})
    if fqn != "smoke.commands.CommandTarget.execute":
        raise AssertionError("fully-qualified name = %r" % fqn)

    jar_matches = lsp.request("workspace/executeCommand", {
        "command": "kotlin-lsp.findTextInDependencyJars", "arguments": ["kotlin/Metadata"]})
    if not isinstance(jar_matches, list):
        raise AssertionError("dependency search result is not a list: %r" % jar_matches)

    jar = _write_jar(uri)
    root = lsp.request("workspace/executeCommand", {
        "command": "kotlin-lsp.listJarClasses", "arguments": [jar]})
    if root.get("packages") != ["sample"] or root.get("classes"):
        raise AssertionError("jar root listing = %r, expected only the 'sample' package" % root)

    api = lsp.request("workspace/executeCommand", {
        "command": "kotlin-lsp.listJarClasses", "arguments": [jar, "sample.api"]})
    # The tree is only useful if a node shows its own level: subpackages listed, classes directly
    # in the package listed, nested classes of subpackages *not* flattened into it.
    if api.get("packages") != ["internal"]:
        raise AssertionError("expected subpackage ['internal'], got %r" % api.get("packages"))
    names = [entry["name"] for entry in api.get("classes", [])]
    if names != ["Client", "Codec"]:
        raise AssertionError("expected ['Client', 'Codec'] directly in sample.api, got %r" % names)
    if api["classes"][0]["entry"] != "sample/api/Client.class":
        raise AssertionError("class entry path is not openable: %r" % api["classes"][0])

    return ("doctor, stack location, FQN, dependency-jar search, and jar package tree "
            "(%d package(s), %d class(es)) answered" % (len(root["packages"]), len(names)))
