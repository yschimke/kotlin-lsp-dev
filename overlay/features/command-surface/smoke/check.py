"""Live checks for the additive workspace command surface."""

FIXTURE = """\
package smoke.commands

class CommandTarget {
    fun execute() = Unit
}
"""


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

    return "doctor, stack location, FQN, and dependency-jar search commands answered"
