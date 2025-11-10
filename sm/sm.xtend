
package org.example.sm.generator

import org.eclipse.emf.ecore.EObject
import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.xtext.generator.AbstractGenerator
import org.eclipse.xtext.generator.IFileSystemAccess2
import org.eclipse.xtext.generator.IGeneratorContext
import org.eclipse.xtext.nodemodel.util.NodeModelUtils
import java.util.List
import java.util.ArrayList

import org.example.sm.sM.Model
import org.example.sm.sM.ValProp

class SMGenerator extends AbstractGenerator {

    override void doGenerate(Resource resource, IFileSystemAccess2 fsa, IGeneratorContext context) {
        if (resource === null || resource.contents.empty) return

        val model = resource.allContents.toIterable.filter(Model).head
        if (model === null) return

        val items = model.props.map[toJsonObject]

        val json = '''
        [
        «FOR it : items SEPARATOR ",\n"»«it»«ENDFOR»
        ]
        '''.toString

        val outName = resource.URI.trimFileExtension.lastSegment + ".json"
        fsa.generateFile(outName, IFileSystemAccess2.DEFAULT_OUTPUT, json)
    }

   def private toJsonObject(ValProp p) {
    val idText   = nodeText(p, "id")
    val vtText   = nodeText(p, "valueType")
    val mustText = nodeText(p, "isMust")
    val depText  = nodeText(p, "dependency")
    val exclusionText  = nodeText(p, "exclusion")
    val extTriggerText = nodeText(p.extend, "triggerValue")
    val extRefText = nodeText(p.extend, "refName")

    val List<String> parts =
        if (idText !== null) idText.split("\\.").toList else newArrayList

    val moduleName = if (parts.size > 0) parts.get(0) else ""
    val vid        = if (parts.size > 0) parts.get(parts.size - 1) else ""

    val mid = mapToAmpersandName(moduleName)

    val dim = extractDimension(vtText)
    val scaleArr = toScaleJsonArray(p)

    '''
   {
      "mid": "«escape(mid)»",
      "id": "«escape(vid)»",
      "valueType": "«escape(vtText)»",
      "isMust": «toBool(mustText)»«IF dim !== null»,
      "dimension": «dim»«ENDIF»«IF scaleArr !== null»,
      "scale": «scaleArr»«ENDIF»«IF depText !== null && depText.length > 0»,
      "dependency": "«escape(depText)»"«ENDIF»«IF exclusionText !== null && exclusionText.length > 0»,
      "exclusion": "«escape(exclusionText)»"«ENDIF»«IF extTriggerText !== null && extRefText !== null»,
      "extend": "«escape(extTriggerText)»:«escape(extRefText)»"«ENDIF»
    }
    '''.toString.trim
}


    def private String mapToAmpersandName(String moduleName) {
        if (moduleName === null) return ""
        val name = moduleName.trim
        val last = if (name.contains(".")) name.substring(name.lastIndexOf(".") + 1) else name
        if (last.startsWith("MODULE_")) {
            return "&" + last.substring("MODULE_".length)
        }
        return "&" + last
    }

    def private Integer extractDimension(String vt) {
        if (vt === null) return null
        val m = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(vt)
        if (m.find) Integer.parseInt(m.group(1)) else null
    }

    def private String toScaleJsonArray(ValProp p) {
        if (p.scale === null) return null
        val f = p.scale.eClass.getEStructuralFeature("values")
        val nodes = NodeModelUtils.findNodesForFeature(p.scale, f)
        if (nodes === null || nodes.empty) return "[]"

        val items = newArrayList
        for (n : nodes) {
            val raw  = n.text.trim                
            val core = unquote(raw)               
            items += toJsonScalar(core)           
        }
        '''[«items.join(", ")»]'''
    }

    def private String toJsonScalar(String s) {
        if (s === null || s.trim.length == 0) return "\"\""
        try {
            Integer.parseInt(s)
            return s
        } catch (NumberFormatException e) {}
        try {
            Double.parseDouble(s)
            return s
        } catch (NumberFormatException e) {}
        return '"' + escape(s) + '"'
    }

    def private String unquote(String s) {
        if (s === null || s.length < 2) return s
        val first = s.charAt(0)
        val last  = s.charAt(s.length - 1)
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return s.substring(1, s.length - 1)
        }
        s
    }

    def private String toBool(String s) {
        if (s === null) return "false"
        if ("True".equalsIgnoreCase(s.trim)) "true" else "false"
    }

    def private String nodeText(EObject owner, String featureName) {
        if (owner === null) return null
        val f = owner.eClass.getEStructuralFeature(featureName)
        if (f === null) return null
        val nodes = NodeModelUtils.findNodesForFeature(owner, f)
        if (nodes === null || nodes.empty) return null
        nodes.map[text].join(" ").trim
    }

    def private String escape(String s) {
        if (s === null) return ""
        s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
