// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, dontTouch}

import firrtl.{RenameMap}
import firrtl.annotations.{SingleTargetAnnotation, ComponentName} // Deprecated
import firrtl.annotations.{ReferenceTarget, ModuleTarget}

// This is currently consumed by a transformation that runs after MIDAS's core
// transformations In FireSim, targeting an F1 host, these are consumed by the
// AutoILA infrastucture (ILATopWiring pass) to generate an ILA that plays nice
// with AWS's vivado flow
case class FpgaDebugAnnotation(target: chisel3.Data) extends ChiselAnnotation {
  def toFirrtl = FirrtlFpgaDebugAnnotation(target.toNamed)
}

case class FirrtlFpgaDebugAnnotation(target: ComponentName) extends
    SingleTargetAnnotation[ComponentName] {
  def duplicate(n: ComponentName) = this.copy(target = n)
}


private[midas] class ReferenceTargetRenamer(renames: RenameMap) {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exactRename(rt: ReferenceTarget): ReferenceTarget = {
    val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
    assert(renameMatches.length == 1)
    renameMatches.head
  }
  def apply(rt: ReferenceTarget): Seq[ReferenceTarget] = {
    renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
  }
}

case class SynthPrintfAnnotation(
    args: Seq[ReferenceTarget],
    mod: ModuleTarget,
    format: String,
    name: Option[String]) extends firrtl.annotations.Annotation {

  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedArgs = args.map(renamer.exactRename)
    val renamedMod = renames.get(mod).getOrElse(Seq(mod)).collect({ case mt: ModuleTarget => mt })
    assert(renamedMod.size == 1) // To implement: handle module duplication or deletion
    Seq(this.copy(args = renamedArgs, mod = renamedMod.head ))
  }
}

// HACK: We're going to reuse the format to find the printf, from which we can grab the printf's enable
private[midas] case class ChiselSynthPrintfAnnotation(
    format: String,
    args: Seq[Bits],
    mod: BaseModule,
    name: Option[String]) extends ChiselAnnotation {
  def toFirrtl() = SynthPrintfAnnotation(args.map(_.toNamed.toTarget), mod.toNamed.toTarget, format, name)
}

// For now, this needs to be invoked on the arguments to printf, not on the printf itself
// Eg. printf(SynthesizePrintf("True.B or False.B: Printfs can be annotated: %b\n", false.B))
object SynthesizePrintf {
  def generateAnnotations(format: String, args: Seq[Bits], name: Option[String]): Printable = {
    val thisModule = Module.currentModule.getOrElse(
      throw new RuntimeException("Cannot annotate a printf outside of a Module"))
    chisel3.experimental.annotate(ChiselSynthPrintfAnnotation(format, args, thisModule, name))
    Printable.pack(format, args:_*)
  }
  def apply(name: String, format: String, args: Bits*): Printable =
    generateAnnotations(format, args, Some(name))

  def apply(format: String, args: Bits*): Printable = generateAnnotations(format, args, None)

  // TODO: Accept a printable -> need to somehow get the format string from it
}

private[midas] case class TraceAnnotation(
    data: Seq[ReferenceTarget],
    enable: Option[ReferenceTarget] = None) extends firrtl.annotations.Annotation {

  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedData = data.flatMap(renamer(_))
    val renamedEnable = enable.map(renamer.exactRename)
    Seq(this.copy(data = renamedData, enable = renamedEnable))
  }
}

private[midas] case class ChiselTraceAnnotation(fields: Seq[Data], enable: Option[Bool]) extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl() = TraceAnnotation(fields.map(_.toNamed.toTarget),
                                   enable = enable.map(_.toNamed.toTarget))
}

object Trace {
  private def generateAnnotations(fields: Seq[Data], enable: Option[Bool]): Unit = {
    chisel3.experimental.annotate(ChiselTraceAnnotation(fields, enable))
    fields.foreach(dontTouch(_))
    enable.foreach(dontTouch(_))
  }
  def apply(fields: Data*): Unit = generateAnnotations(fields, None)
  def apply(fields: Seq[Data], enable: Bool): Unit = generateAnnotations(fields, Some(enable))
}