package org.example.am.generator

import org.eclipse.emf.ecore.resource.Resource

import org.eclipse.xtext.generator.AbstractGenerator
import org.eclipse.xtext.generator.IGeneratorContext
import org.eclipse.xtext.generator.IFileSystemAccess2
import org.example.am.aM.AvailableModules
import org.example.am.aM.ModuleDecl
import org.example.am.aM.Required
import org.eclipse.xtext.nodemodel.util.NodeModelUtils
import org.example.am.aM.AMPackage

class AMJsonGenerator extends AbstractGenerator {

  override doGenerate(Resource resource, IFileSystemAccess2 fsa, IGeneratorContext context) {
    if (resource.contents.empty) return
    val root = resource.contents.head as AvailableModules
    val base = resource.URI.trimFileExtension.lastSegment
    fsa.generateFile(base + ".json", root.toJson)
  }

  def private CharSequence toJson(AvailableModules m) '''
[
«FOR d : m.modules SEPARATOR ",\n"»
  «d.toJsonObject»
«ENDFOR»
]
'''

def private CharSequence toJsonObject(ModuleDecl d) {
  val isReq = d.required == Required::REQ
  val hasRep = !NodeModelUtils.findNodesForFeature(
    d, AMPackage.Literals.MODULE_DECL__REPEAT
  ).empty

  val minVal = if (isReq) 1 else 0
  val int maxVal =
    if (hasRep) Math.max(d.repeat, minVal)
    else if (isReq) 1 else 1

  val fields = newArrayList(
    '"name": "' + d.id + '"',
    '"required": ' + isReq,
    '"min": ' + minVal,
    '"max": ' + maxVal
  )
  fields += '"val_min": ' + (if (d.range !== null) d.range.min.toString else "null")
  fields += '"val_max": ' + (if (d.range !== null) d.range.max.toString else "null")

  '''
{
  «FOR f : fields SEPARATOR ",\n"»  «f»«ENDFOR»
}'''
}
}
