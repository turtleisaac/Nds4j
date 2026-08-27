package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Core;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library's version is written down twice: once as the Maven coordinate in {@code pom.xml},
 * and once as the {@code VERSION} array that {@link Core#getVersionNumber()} reports at runtime.
 * Two hand-maintained copies of the same fact drift, and when they do the failure is silent --
 * a consumer that branches on {@code Core.getVersionNumber()} makes its decision against a
 * number that has nothing to do with the jar it actually resolved.
 * <p>
 * This test is the thing that notices.
 */
@DisplayName("The reported version matches the published Maven coordinate")
class VersionConsistencyTest
{
    /** Reads {@code /project/version} -- the project's own version, not a dependency's. */
    private static String pomVersion() throws Exception
    {
        File pom = new File(System.getProperty("basedir", "."), "pom.xml");
        assertThat(pom)
                .as("this test locates pom.xml relative to the module directory")
                .exists();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document document = factory.newDocumentBuilder().parse(pom);

        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            // Only a direct child of <project> is the project's own version; a <version> nested
            // inside <dependencies> belongs to something else entirely.
            if (child.getNodeType() == Node.ELEMENT_NODE && "version".equals(child.getNodeName()))
                return child.getTextContent().trim();
        }
        throw new AssertionError("pom.xml declares no top-level <version>");
    }

    /**
     * The pom version without any {@code -SNAPSHOT} or other qualifier.
     * <p>
     * Between releases the pom carries a qualifier and the runtime version cannot: {@code VERSION}
     * is an {@code int[]}, so it holds only the three numbers. Comparing the numbers is still the
     * thing worth checking - it is the digits that drift, and a qualifier the pom alone can say.
     */
    private static String releasedPartOf(String pomVersion)
    {
        int qualifier = pomVersion.indexOf('-');
        return qualifier < 0 ? pomVersion : pomVersion.substring(0, qualifier);
    }

    @Test
    @DisplayName("Core.getVersionNumber() equals the version in pom.xml")
    void runtimeVersionMatchesPom() throws Exception
    {
        assertThat(Core.getVersionNumber())
                .as("Core.VERSION and pom.xml must be bumped together")
                .isEqualTo(releasedPartOf(pomVersion()));
    }

    /**
     * A published coordinate is permanent, so the version on main must never be one that has
     * already been released - main moved eight commits past the v1.0.0 tag while still declaring
     * 1.0.0, which meant "Nds4j 1.0.0" named two different jars: the one on Central and the one
     * every downstream CI was building from source. Nothing reported the difference.
     * <p>
     * A qualifier is what says "not that release yet". This does not assert which version is
     * next - only that development is not sitting on a number that is already taken.
     */
    @Test
    @DisplayName("an in-development version is qualified, so it cannot be mistaken for a release")
    void developmentVersionIsQualified() throws Exception
    {
        String pom = pomVersion();

        assertThat(pom)
                .as("a release is cut by dropping the qualifier at tag time; carrying a bare "
                        + "version on main is how a released coordinate comes to mean two jars")
                .endsWith("-SNAPSHOT");
    }

    @Test
    @DisplayName("the reported version is a well-formed semantic version")
    void versionIsSemantic()
    {
        // The README commits this library to semantic versioning, so the shape is part of the
        // contract consumers rely on.
        assertThat(Core.getVersionNumber())
                .as("Nds4j documents that it follows semantic versioning")
                .matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    @DisplayName("the structured accessor agrees with the formatted string")
    void componentsAgreeWithFormattedString()
    {
        String expected = String.format("%d.%d.%d",
                Core.getSpecificVersionNumber(Core.VersionData.MAJOR),
                Core.getSpecificVersionNumber(Core.VersionData.MINOR),
                Core.getSpecificVersionNumber(Core.VersionData.PATCH));

        assertThat(Core.getVersionNumber())
                .as("the two public ways of asking for the version must not disagree")
                .isEqualTo(expected);
    }
}
