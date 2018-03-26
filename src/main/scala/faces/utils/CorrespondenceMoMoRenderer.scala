package faces.utils

/*
 * Copyright University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import scalismo.faces.color.RGBA
import scalismo.faces.image.PixelImage
import scalismo.faces.landmarks.TLMSLandmark2D
import scalismo.faces.mesh.VertexColorMesh3D
import scalismo.faces.momo.MoMo
import scalismo.faces.parameters.RenderParameter
import scalismo.faces.render.TriangleRenderer.TriangleFragment
import scalismo.faces.render.{PixelShader, TriangleRenderer}
import scalismo.faces.sampling.face._
import scalismo.geometry.{Vector, _3D}
import scalismo.mesh.{MeshSurfaceProperty, SurfacePointProperty}
import scalismo.utils.Memoize

/** MoMoRenderer that additionally allows to render correspondence images.
  * The renderImage method gives the same result as a standard MoMoRenderer. */
class CorrespondenceMoMoRenderer(override val model: MoMo, override val clearColor: RGBA) extends MoMoRenderer(model, clearColor) {

  def renderCorrespondenceImage(parameters: RenderParameter): PixelImage[Option[TriangleFragment]] = {
    val inst = instance(parameters)
    TriangleRenderer.renderCorrespondenceImage(inst.shape, parameters.pointShader, parameters.imageSize.width, parameters.imageSize.height)
  }

  /** render the image described by the parameters */
  override def renderImage(parameters: RenderParameter): PixelImage[RGBA] = {
    val correspondenceImage = renderCorrespondenceImage(parameters)
    val inst = instance(parameters)
    val shader: PixelShader[RGBA] = parameters.pixelShader(inst)
    correspondenceImage.map{ px => if(px.isDefined) shader(px.get) else clearColor }
  }

  /** get a cached version of this renderer */
  override def cached(cacheSize: Int) = new CorrespondenceMoMoRenderer(model, clearColor) {
    private val imageRenderer = Memoize(super.renderImage, cacheSize)
    private val correspondenceImageRenderer = Memoize(super.renderCorrespondenceImage, cacheSize)
    private val meshRenderer = Memoize(super.renderMesh, cacheSize)
    private val maskRenderer = Memoize((super.renderMask _).tupled, cacheSize)
    private val lmRenderer = Memoize((super.renderLandmark _).tupled, cacheSize * allLandmarkIds.length)
    private val instancer = Memoize(super.instance, cacheSize)

    override def renderImage(parameters: RenderParameter): PixelImage[RGBA] = imageRenderer(parameters)
    override def renderCorrespondenceImage(parameters: RenderParameter): PixelImage[Option[TriangleFragment]] = correspondenceImageRenderer(parameters)
    override def renderLandmark(lmId: String, parameter: RenderParameter): Option[TLMSLandmark2D] = lmRenderer((lmId, parameter))
    override def renderMesh(parameters: RenderParameter): VertexColorMesh3D = meshRenderer(parameters)
    override def instance(parameters: RenderParameter): VertexColorMesh3D = instancer(parameters)
    override def renderMask(parameters: RenderParameter, mask: MeshSurfaceProperty[Int]): PixelImage[Int] = maskRenderer((parameters, mask))
  }
}

object CorrespondenceMoMoRenderer {
  def apply(model: MoMo, clearColor: RGBA) = new CorrespondenceMoMoRenderer(model, clearColor)
  def apply(model: MoMo) = new CorrespondenceMoMoRenderer(model, RGBA.BlackTransparent)
}

abstract class RenderFromCorrespondenceImage[A](correspondenceMoMoRenderer: CorrespondenceMoMoRenderer) extends ParametricImageRenderer[A]{
  override def renderImage(parameters: RenderParameter): PixelImage[A]
}

case class DepthMapRenderer(correspondenceMoMoRenderer: CorrespondenceMoMoRenderer) extends RenderFromCorrespondenceImage[RGBA](correspondenceMoMoRenderer: CorrespondenceMoMoRenderer) {
  override def renderImage(parameters: RenderParameter): PixelImage[RGBA] = {
    val correspondenceImage = correspondenceMoMoRenderer.renderCorrespondenceImage(parameters)
    val depthMap = correspondenceImage.map{ px=>
      if(px.isDefined){
        val frag = px.get
        val tId = frag.triangleId
        val bcc = frag.worldBCC
        val mesh = frag.mesh

        val posModel = mesh.position(tId, bcc)
        val posEyeCoordinates = parameters.modelViewTransform(posModel)

        Some((parameters.view.eyePosition-posEyeCoordinates).norm)
      }else{
        None
      }
    }
    val values  = depthMap.values.toIndexedSeq.flatten
    val ma = values.max
    val mi = values.min
    val mami = ma-mi
    depthMap.map{d=>
      if(d.isEmpty)
        RGBA(0.0)
      else {
        RGBA(1.0 - (d.get - mi)/mami)
      }
    }
  }
}

case class CorrespondenceColorImageRenderer(correspondenceMoMoRenderer: CorrespondenceMoMoRenderer, backgroundColor: RGBA = RGBA.Black) extends RenderFromCorrespondenceImage[RGBA](correspondenceMoMoRenderer){
  val reference: VertexColorMesh3D = correspondenceMoMoRenderer.model.mean
  val normalizedReference: SurfacePointProperty[RGBA] = {
    val extent = reference.shape.pointSet.boundingBox.extent.toBreezeVector
    val min = reference.shape.pointSet.boundingBox.origin.toBreezeVector
    val extV = reference.shape.pointSet.boundingBox.extent
    val minV = reference.shape.pointSet.boundingBox.origin
    val normalizedPoints = reference.shape.pointSet.points.map(p => (p.toBreezeVector - min) :/ extent).map(f => Vector[_3D](f.toArray)).toIndexedSeq
    SurfacePointProperty(reference.shape.triangulation, normalizedPoints.map(d=>RGBA(d.x,d.y, d.z)))
  }

  override def renderImage(parameters: RenderParameter): PixelImage[RGBA] = {
    val correspondenceImage = correspondenceMoMoRenderer.renderCorrespondenceImage(parameters)
    correspondenceImage.map{px =>
      if(px.isDefined) {
        val frag = px.get
        normalizedReference(frag.triangleId, frag.worldBCC)
      }else {
        backgroundColor
      }
    }
  }
}

