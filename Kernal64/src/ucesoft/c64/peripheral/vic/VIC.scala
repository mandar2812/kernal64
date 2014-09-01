package ucesoft.c64.peripheral.vic

import ucesoft.c64.cpu.Memory
import ucesoft.c64.Chip
import ucesoft.c64.ChipID
import ucesoft.c64.Log
import ucesoft.c64.Clock
import ucesoft.c64.ClockEvent
import java.awt.Image
import java.awt.geom.Rectangle2D
import Palette._
import java.awt.Graphics2D
import ucesoft.c64.cpu.CPU6510Mems
import java.awt.event.ActionEvent
import java.util.Arrays
import ucesoft.c64.C64Component
import ucesoft.c64.C64ComponentType
import ucesoft.c64.cpu.RAMComponent
/*
object VIC extends App {
  import CPU6510Mems._
  import javax.swing._
  System.setProperty("sun.java2d.opengl","true")
  System.setProperty("sun.java2d.accthreshold","0")
  val mem = new MAIN_MEMORY(true)
  mem.init
  var base = 1024
  for(m <- 0 until 1000) if (m < 256) mem.write(base + m,m) else mem.write(base + m,32)
  for(c <- 0xD800 until 0xD800 + 1000) mem.write(c,14)
  val bankedMem = new BankedMemory(mem,mem.CHAR_ROM,mem.COLOR_RAM)
  //Log.setDebug
  val frame = new JFrame("Test")
  frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
  val vic = new VIC(bankedMem,x => {},x => {})
  val display = new Display(vic.SCREEN_WIDTH,vic.SCREEN_HEIGHT,"C64",frame)
  vic.setDisplay(display)
  vic.write(0xD011,0x9B)
  vic.write(0xD016,8)
  vic.write(0xD018,0x14)
  vic.write(0xD021,6)
  vic.write(0xD022,1)
  vic.write(0xD023,2)
  vic.write(0xD020,14)
  // sprite #x --------
  for(i <- 0 to 7) mem.write(2040 + i,1)  
  //for(i <- 0 to 62) mem.write(64+i,255)
  
  for(y <- 0 to 20) {
    for(x <- 0 to 2) if (y == 0 || y == 20) mem.write(64 + y*3 + x,0x78) else mem.write(64 + y*3 + x,255)
  }
  
  /*
  for(y <- 0 to 20;x <- 0 to 2) x match {
    case 0 => if (y == 0 || y == 20) mem.write(16 + y*3 + x,255) else mem.write(16 + y*3 + x,128)
    case 1 => if (y == 0 || y == 20) mem.write(16 + y*3 + x,255) else mem.write(16 + y*3 + x,0)
    case 2 => if (y == 0 || y == 20) mem.write(16 + y*3 + x,255) else mem.write(16 + y*3 + x,1)
  }
  */ 
  for(y <- 0 to 20) println("%3d %3d %3d".format(mem.read(64 + y*3),mem.read(64 + y*3 + 1),mem.read(64 + y*3 + 2)))
  var x = 24
  var y = 229
  for(i <- 0 to 0) {
    vic.write(0xD000 + i*2,x)
    vic.write(0xD000 + i*2 + 1,y)
    x += 12
    //y += 12
  }
  mem.write(base + 40 * 24,1)
  /*
  vic.write(0xD000,24)
  vic.write(0xD001,51) 
  vic.write(0xD002,49)
  vic.write(0xD003,61)
  */
  vic.write(0xD01B,1)
  //vic.write(0xD000,63)
  //vic.write(0xD010,1)
  vic.write(0xD015,255)  // #0+1
  // colors
  for(i <- 0 to 7) vic.write(0xD027 + i,i)
  //vic.write(0xD017,1)
  //vic.write(0xD01D,1)
  vic.write(0xD01C,0) // multi
  vic.write(0xD025,2)
  vic.write(0xD026,3)
  //vic.write(0xD01A,255)
  // ------------------
  frame.setSize(320,200)
  val button = new JButton("Clock")
  button.addActionListener(new java.awt.event.ActionListener {
    def actionPerformed(e:java.awt.event.ActionEvent) { for(_ <- 1 to 63 * 8) vic.clock(0) }
  })
  frame.getContentPane.add("Center",display)
  //frame.getContentPane.add("North",button)
  frame.setVisible(true)
  Clock.setSystemClock() { cycles =>
    vic.clock(cycles)
  }
  Clock.systemClock.play
}
*/
final class VIC(mem: Memory,
  irqAction: (Boolean) => Unit,
  baLow: (Long) => Unit,
  debug: Boolean = false,
  clip: Boolean = true) extends Chip with RAMComponent {
  override lazy val componentID = "VIC 6569"
  val name = "VIC"
  val isRom = false
  val length = 1024
  val isActive = true
  val startAddress = 0xD000
  val id = ChipID.VIC

  // ----------------------- Constants --------------------------------------------------------------------
  private[this] val CYCLE_FOR_FIRST_XCOORD = 12
  private[this] val RASTER_LINES = 312
  private[this] val RASTER_CYCLES = 63
  val SCREEN_WIDTH = RASTER_CYCLES * 8
  val SCREEN_HEIGHT = RASTER_LINES
  private[this] val PIXELS_PER_LINE = RASTER_CYCLES * 8
  private[this] val LEFT_RIGHT_FF_COMP = Array(Array(0x1F, 0x14F), Array(0x18, 0x158)) // first index is CSEL's value 0 or 1, second index is 0=left, 1=right 
  private[this] val TOP_BOTTOM_FF_COMP = Array(Array(0x37, 0xF7), Array(0x33, 0xFB)) // first index is RSEL's value 0 or 1, second index is 0=left, 1=right
  private[this] val COLOR_ADDRESS = 0xD800
  private[this] val BLANK_TOP_LINE = 15
  private[this] val BLANK_BOTTOM_LINE = 300
  private[this] val BLANK_LEFT_CYCLE = 9
  private[this] val BLANK_RIGHT_CYCLE = 60
  private[this] val BA_CYCLES_FOR_CHARS = 40
  private[this] val BA_CYCLES_PER_SPRITE = 3
  val VISIBLE_SCREEN_WIDTH = (BLANK_RIGHT_CYCLE - BLANK_LEFT_CYCLE - 1) * 8
  val VISIBLE_SCREEN_HEIGHT = BLANK_BOTTOM_LINE - BLANK_TOP_LINE + 1
  val SCREEN_ASPECT_RATIO = VISIBLE_SCREEN_WIDTH.toDouble / VISIBLE_SCREEN_HEIGHT
  // ----------------------- INTERNAL REGISTERS -----------------------------------------------------------
  private[this] var videoMatrixAddress = 0
  private[this] var characterAddress = 0
  private[this] var bitmapAddress = 0
  private[this] val vml_p = Array.fill(40)(0) // video matrix line for characters pointers
  private[this] val vml_c = Array.fill(40)(0) // video matrix line for characters colors
  private[this] var vcbase, vc = 0
  private[this] var rc = 0 // row counter
  private[this] var vmli = 0 // video matrix line index
  private[this] var isInDisplayState = false // display or idle state
  private[this] var yscroll = 0 // y scroll value 0-7
  private[this] var xscroll = 0 // x scroll value 0-7
  private[this] var rsel = 0 // 1 => 25 rows, 0 => 24 rows
  private[this] var den = false // blank screen if false
  private[this] var denOn30 = false // den value at raster line $30
  private[this] var bmm = false // bitmap mode: true enabled
  private[this] var ecm = false // extended color mode: true enabled
  private[this] var csel = 0 // 1 => 40 cols, 0 => 38 cols
  private[this] var mcm = false // multi color mode: true enabled
  private[this] var res = false // video enabled: false enabled
  // borders
  private[this] var mainBorderFF = false // main border flip flop
  private[this] var verticalBorderFF = false // vertical border flip flop

  private[this] var rasterCycle = 0 // horizontal cycle 0-62
  private[this] val SPRITE_READ_CYCLE = {
    val cycles = Array.fill[Int](RASTER_CYCLES)(-1)
    cycles(58) = 0
    cycles(60) = 1
    cycles(62) = 2
    cycles(1)  = 3
    cycles(3)  = 4
    cycles(5)  = 5
    cycles(7)  = 6
    cycles(9)  = 7
    cycles
  }
  private[this] val SPRITE_BA_CYCLE = {
    val baCycles = Array.fill[Int](RASTER_CYCLES)(-1)
    for(i <- 0 until SPRITE_READ_CYCLE.length) {
      if (SPRITE_READ_CYCLE(i) != -1) {
        var baIndex = i - BA_CYCLES_PER_SPRITE
        if (baIndex < 0) baIndex += RASTER_CYCLES
        baCycles(baIndex) = SPRITE_READ_CYCLE(i)
      } 
    }
    baCycles
  }
  // graphics management
  private[this] var isBlank = false // valid inside drawCycle: tells if we are in the blank area
  private[this] var display: Display = null // the display
  private[this] var displayMem: Array[Int] = null
  private[this] val cyclePixels = Array.fill(8)(TransparentPixel) // the pixels of the cycle that has to be drawn 
  private[this] var firstModPixelX, firstModPixelY = 0 // first x,y pixel coordinate modified
  private[this] var lastModPixelX, lastModPixelY = 0 // last x,y pixel coordinate modified
  private[this] var lightPenEnabled = false
  // --------------------- DEBUG --------------------------------------------------------------------------
  private[this] var traceRasterLineInfo = false
  private[this] val traceRasterLineBuffer = Array.fill(SCREEN_WIDTH)("")
  private[this] var traceRasterLine = 0
  // ------------------------ PUBLIC REGISTERS ------------------------------------------------------------
  /*
   * $D000 - $D00F
   * x,y coordinate of sprite i, 0 <= i <= 7
   */
  private[this] val spriteXYCoord = Array.fill(16)(0)
  /*
   * $D010
   * 9th bit of x coordinate of sprite i
   */
  private[this] var spriteXCoord9thBit = 0
  /*
   * $D011
	Initial Value: %10011011
	Bit responsibilities:
	Bit#0-#2: Screen Soft Scroll Vertical
	Bit#3: Switch between 25 or 24 visible rows
	Bit#4: Switch VIC-II output on/off
	Bit#5: Turn Bitmap Mode on/off
	Bit#6: Turn Extended Color Mode on/off
	Bit#7: 9th Bit for $D012 Rasterline counter 
  */
  private[this] var controlRegister1 = 0x9B
  /*
   * $D012
    When Reading:Return current Rasterline
    When Writing:Define Rasterline for Interrupt triggering
    Bit#7 of $D011 is (to be) set if line number exceeds 255
   */
  private[this] var rasterLine = 0x100 // real raster line 0-8 bits  
  private[this] var rasterLatch = 0
  /*
   * $D013 - $D014
   * Light Pen XY Coord
   */
  private[this] val lightPenXYCoord = Array(0, 0)
  private[this] var canUpdateLightPenCoords = true
  /*
   * $D015
   * Each Bit corresponds to a Sprite. If set high the corresponding Sprite is enabled on Screen
   */
  private[this] var spriteEnableRegister = 0
  /*
   * $D016
   * Initial Value: %00001000
	 Bit responsibilities:
	 Bit#0-#2: Screen Soft Scroll Horizontal
 	 Bit#3: Switch betweem 40 or 38 visible columns
	 Bit#4: Turn Multicolor Mode on/off
	 Bit#5-#7: not used 
   */
  private[this] var controlRegister2 = 8
  /*
   * $D017
   * Every Bit corresponds to one Sprite. If set high, the Sprite will be stretched vertically x2
   */
  private[this] var spriteYExpansion = 0
  /*
   * $D018
   * Initial Value: %00010100
	 Bit responsibilities:
	 Bit#0: not used
	 Bit#1-#3: Address Bits 11-13 of the Character Set (*2048)
	 Bit#4-#7: Address Bits 10-13 of the Screen RAM (*1024) 
   */
  private[this] var vicBaseAddress = 0x14
  /*
   * $D019
   * Initial Value: %00001111 (for latch)
	 Bit responsibilities:
	 Bit#0: Interrupt by Rasterline triggered when high
	 Bit#1: Interrupt by Sprite-Background collision triggered when high
	 Bit#2: Interrupt by Sprite-Sprite collision triggered when high
	 Bit#3: Interrupt by Lightpen impulse triggered when high
	 Bit#4-#6: not used
	 Bit#7: If set high at least one of the Interrupts above were triggered 
   */
  private[this] var interruptControlRegister = 0
  /*
   * $D01A
   * Initial Value: %00000000
	 Bit responsibilities:
	 Bit#0: Request Interrupt by Rasterline by setting high
	 Bit#1: Request Interrupt by Spite-Background collision by setting high
	 Bit#2: Request Interrupt by Sprite-Sprite collision by setting high
	 Bit#3: Request Interrupt by Lightpen impulse by setting high
	 Bit#4-#7: not used  
   */
  private[this] var interruptMaskRegister = 0
  /*
   * $D01B
   * Each Bit corresponds to a Sprite. If set high, the Background overlays the Sprite, if set low, the Sprite overlays Background.
   */
  private[this] var spriteCollisionPriority = 0
  /*
   * $D01C
   * Each Bit correspondents to a Sprite. If set high, the Sprite is considered to be a Multicolor-Sprite
   */
  private[this] var spriteMulticolor = 0
  /*
   * $D01D
   * Each Bit corresponds to a Sprite. If set high, the Sprite will be stretched horzontally x2
   */
  private[this] var spriteXExpansion = 0
  /*
   * $D01E
   * Each Bit corresponds to a Sprite. 
   * If two sprites collide, then corresponding Bits involved in the collision are set to high. 
   * This event will also set Bit#2 of the Interrupt Request Register high.
   */
  private[this] var spriteSpriteCollision = 0
  /*
   * $D01F
   * Each Bit corresponds to a Sprite. 
   * If a sprite collides with the backgroud, then its Bit is set to high. This event will also set Bit#1 of the Interrupt Request Register high.
   */
  private[this] var spriteBackgroundCollision = 0
  /*
   * $D020
   * Set Border Color to one of the 16 Colors ($00-$0F)
   */
  private[this] var borderColor = 0
  /*
   * $D021 - $D024
   * Set Background Color 0 - 3 to one of the 16 Colors ($00-$0F)
   */
  private[this] var backgroundColor = Array(0, 0, 0, 0)
  /*
   * $D025 - $D026
   * Set Color 1 - 2 shared by Multicolor Sprites
   */
  private[this] var spriteMulticolor01 = Array(0, 0)
  /*
   * $D027 - $D02E
   * Set individual color for Sprite#0 - #7
   */
  private[this] var spriteColor = Array(0, 0, 0, 0, 0, 0, 0, 0)

  // ----------------------------- SPRITE -------------------------------------------------
  private[this] class Sprite(index: Int,
    var enabled: Boolean = false,
    var x: Int = 0,
    var y: Int = 0,
    var xexp: Boolean = false,
    var color: Int = 0,
    var isMulticolor: Boolean = false,
    var dataPriority: Boolean = false) extends Shifter with C64Component {
    val componentID = "Sprite " + index
    val componentType = C64ComponentType.INTERNAL
    
    private[this] var counter = 0
    private[this] var gdata = 0
    private[this] var _yexp = false
    private[this] var memoryPointer = 0
    private[this] var mcbase, mc = 0
    var dma = false
    private[this] var expansionFF = true
    private[this] var xexpCounter = 0
    private[this] var display = false
    private[this] var lastLine = false
    var painting = false
    var hasPixels = false
    private[this] val pixels = Array.fill[AbstractPixel](8)(TransparentPixel)
    
    override def toString = s"Sprite #${index} cnt=${counter} data=${Integer.toBinaryString(gdata & 0xFFFFFF)} en=${enabled} hasPixels=${hasPixels} x=${x} y=${y} xexp=${xexp} yexp=${_yexp} color=${color} mcm=${isMulticolor} pr=${dataPriority} memP=${memoryPointer} mcbase=${mcbase} mc=${mc} dma=${dma} display=${display} ff=${expansionFF}"

    override def getProperties = {
      properties.setProperty("Enabled",enabled.toString)
      properties.setProperty("X",x.toString)
      properties.setProperty("Y",y.toString)
      properties.setProperty("X expansion",xexp.toString)
      properties.setProperty("Y expansion",_yexp.toString)
      properties.setProperty("Memory pointer",Integer.toHexString(memoryPointer))
      properties.setProperty("Multi color mode",isMulticolor.toString)
      properties.setProperty("Color",Integer.toHexString(color))
      properties
    }
    
    def yexp = _yexp
    final def displayable = (display || lastLine)

    def yexp_=(v: Boolean) {
      _yexp = v
      if (!v) expansionFF = true
    }

    def getPixels = pixels

    override def clear {
      counter = 0
      reset
    }
    
    def init {}

    @inline final def reset {
      hasPixels = false
      var i = 0
      while (i < 8) {
        pixels(i) = TransparentPixel
        i += 1
      }
    }

    @inline final def producePixels {
      val xcoord = xCoord
      var i = 0
      while (i < 8) {
        //compareAndSet(xcoord + i)
        if (!painting && x == xcoord + i && (display || lastLine)) painting = true
        if (painting && !isFinished) {
          pixels(i) = shift
          //println(s"Sprite #${s} shifted a pixel ${sprite.pixels(i)} in ${i} raster=${rasterLine} cycle=${rasterCycle} ${sprites(s)}")
        }
        i += 1
      }
    }

    final def setData(gdata: Int) {
      this.gdata = gdata
      counter = 0
      //Log.debug(s"Sprite #${index} reset pixels!!")
      hasPixels = false
    }

    @inline final def isFinished = {
      val finished = counter == (if (xexp) 48 else 24)
      if (finished) {
        painting = false
        if (lastLine) lastLine = false
      }
      finished
    }

    final def shift = {
      val pixel = if (!isMulticolor) { // no multicolor        
        if (xexp) {
          if (xexpCounter == 0) gdata <<= 1
          xexpCounter = (xexpCounter + 1) % 2
        } else gdata <<= 1
        val cbit = (gdata & 0x1000000) == 0x1000000
        //if (cbit) new Pixel(VIC_RGB(color)) else TransparentPixel
        if (cbit) getPixel(color) else TransparentPixel
      } else { // multicolor
        if ((counter & 1) == 0) {
          if (xexp) {
            if (xexpCounter == 0) gdata <<= 2
            xexpCounter = (xexpCounter + 1) % 2
          } else gdata <<= 2
        }
        val cbit = (gdata & 0x3000000)
        cbit match {
          case 0x0000000 => TransparentPixel
          case 0x1000000 => getPixel(spriteMulticolor01(0))
          case 0x2000000 => getPixel(color)
          case 0x3000000 => getPixel(spriteMulticolor01(1))
        }
      }
      counter += 1
      if (!pixel.isTransparent) hasPixels = true
      pixel.behind = dataPriority
      pixel.spriteIndex = index
      if (traceRasterLineInfo) pixel.source = 'S'
      pixel
    }

    @inline def readMemoryData(cycles: Long) {
      // p-accesses
      memoryPointer = mem.read(videoMatrixAddress + 1016 + index) << 6//* 64
      // s-accesses
      if (dma) {
        var data = 0
        var i = 16
        while (i >= 0) {
          data |= (mem.read(memoryPointer + mc) & 0xFF) << i
          mc += 1
          i -= 8
        }
        setData(data)
        //Log.fine("Sprite #%d. Loaded data=%4X on raster %d mcbase=%d mc=%d".format(index,data,rasterLine,mcbase,mc))
        //println("Sprite #%d. Loaded data=%4X on raster %d mcbase=%d mc=%d".format(index,data,rasterLine,mcbase,mc))
      }
    }

    def checkForCycle(cycle: Int) = cycle match {
    case 15 =>
      if (expansionFF) mcbase += 2
      case 16 =>
        if (expansionFF) {
          mcbase += 1
//          if (!yexpClearedOn15) mcbase = mc
//          else mcbase = (0x2A & (mcbase & mc)) | (0x15 & (mcbase | mc)) // patch from VIC-Addentum

          if (mcbase == 63) {
            dma = false
            if (display) lastLine = true
            display = false
            //Log.debug(s"Sprite #${index} NOT displayable anymore on raster ${rasterLine}")
          }
        }
      case 54 =>
        if (_yexp) expansionFF = !expansionFF
        //if (enabled && rasterLine < BLANK_BOTTOM_LINE && (y) == (rasterLine & 0xFF) && !dma) {
        if (enabled && rasterLine < 270 && (y) == (rasterLine & 0xFF) && !dma) {
          dma = true
          mcbase = 0
          if (_yexp) expansionFF = false
          //Log.debug(s"Sprite #${index}. " + this)
          //println(s"Sprite #${index} DMA=true on rasterLine=${rasterLine}")
        }
      case 56 =>
        if (!dma) painting = false
      case 57 =>
        mc = mcbase
        //if (dma && rasterLine < BLANK_BOTTOM_LINE && (y) == (rasterLine & 0xFF)) {
        if (dma && rasterLine < 270 && (y) == (rasterLine & 0xFF)) {
          display = true
          //Log.debug(s"Sprite #${index} displayable. " + this)
        }
      case _ => // do nothing
    }
  }
  private[this] val sprites = Array(new Sprite(0), new Sprite(1), new Sprite(2), new Sprite(3), new Sprite(4), new Sprite(5), new Sprite(6), new Sprite(7))
  // ------------------------------ SHIFTERS ----------------------------------------------
  private[this] abstract class AbstractPixel {
    var source: Char = '?'
    var colorIndex = 0
    private[this] var _behind = false
    private[this] var _spriteIndex = 0
    var color: Int
    val isTransparent = false
    var isForeground: Boolean
    // for sprites only
    @inline final def spriteIndex = _spriteIndex
    @inline final def spriteIndex_=(spriteIndex: Int) = _spriteIndex = spriteIndex
    @inline final def behind = _behind
    @inline final def behind_=(behind: Boolean) = _behind = behind
  }
  final private class Pixel( final var color: Int, final var isForeground: Boolean = true) extends AbstractPixel
  final private case object TransparentPixel extends AbstractPixel {
    source = 'T'
    final var color = 0
    final var isForeground = false
    final override val isTransparent = true
  }
  private[this] val BLACK_PIXEL = { val pixel = new Pixel(VIC_RGB(0), false); pixel.source = 'N'; pixel }
  private[this] val PIXEL_CACHE = Array.fill(1000)(new Pixel(0, false))
  private[this] var pixelCacheIndex = 0

  @inline private def getPixel(color: Int, isForeground: Boolean = true) = {
    val pixel = PIXEL_CACHE(pixelCacheIndex)
    pixelCacheIndex = (pixelCacheIndex + 1) % PIXEL_CACHE.length
    pixel.color = VIC_RGB(color)
    if (traceRasterLineInfo) pixel.colorIndex = color
    pixel.isForeground = isForeground
    pixel
  }

  private[this] trait Shifter {
    def setData(gdata: Int)
    protected def shift: AbstractPixel
    def producePixels
    def isFinished: Boolean
    def clear
    def getPixels: Array[AbstractPixel]
  }

  private[this] object BorderShifter extends Shifter {
    private[this] var counter = 0
    private[this] var xcoord = 0
    private[this] var color: AbstractPixel = _
    private[this] val ALL_TRANSPARENT = Array.fill[AbstractPixel](8)(TransparentPixel)
    private[this] val pixels = Array.fill[AbstractPixel](8)(TransparentPixel)
    private[this] var drawBorder = true

    def getPixels = if (drawBorder) pixels else ALL_TRANSPARENT
    def clear { counter = 0 }

    final def isFinished = counter == 8
    final def setData(gdata: Int) {
      counter = 0
      xcoord = xCoord
      color = getPixel(borderColor) //new Pixel(VIC_RGB(borderColor))
      if (traceRasterLineInfo) color.source = 'B'
    }
    final def producePixels {
      // optimization
      drawBorder = rasterLine < TOP_BOTTOM_FF_COMP(rsel)(0) ||
        rasterLine > TOP_BOTTOM_FF_COMP(rsel)(1) ||
        rasterCycle > 15 ||
        rasterCycle < 54
      if (!drawBorder) return
      var i = 0
      while (!isFinished) {
        pixels(i) = shift
        i += 1
      }
    }
    @inline final protected def shift = {
      counter += 1
      if (isBlank) TransparentPixel
      else {
        checkBorderFF(xcoord)
        xcoord += 1
        if (mainBorderFF || verticalBorderFF) color else TransparentPixel
      }
    }
  }
  /**
   * Graphics Shifter for text/bitmap & blank lines
   */
  private object GFXShifter extends Shifter {
    private[this] var counter = 0
    private[this] var gdata = 0
    private[this] var xscrollBuffer = Array.fill(8)(BLACK_PIXEL)
    private[this] var firstPixel = true
    private[this] val pixels = Array.fill[AbstractPixel](8)(TransparentPixel)

    private[this] var mcm, ecm, isBlank = false
    private[this] var vml_p, vml_c: Array[Int] = _
    private[this] var vmli = 0

    def getPixels = pixels

    final def setData(gdata: Int) {
      this.gdata = gdata
      counter = 0
    }

    final override def clear {
      counter = 0
      reset
    }
    final def reset = firstPixel = true
    final def isFinished = counter == 8
    @inline final protected def shift = {
      val isInvalidMode = mcm && ecm
      val pixel = if (isInvalidMode || isBlank || gdata < 0) BLACK_PIXEL
      else if (!bmm) { // text mode        
        val mc = (vml_c(vmli) & 8) == 8
        val multicolor = mcm && mc
        if (!multicolor) { // standard
          gdata <<= 1
          val cbit = (gdata & 0x100) == 0x100
          if (cbit) { // foreground
            if (isInDisplayState) getPixel(if (mcm) vml_c(vmli) & 7 else vml_c(vmli))
            else getPixel(0,true)
          }
          else { // background
            if (isInDisplayState) { 
              val backIndex = if (ecm) (vml_p(vmli) >> 6) & 3 else 0
              getPixel(backgroundColor(backIndex), false)
            }
            else getPixel(backgroundColor(0),false)
          }
        } else { // multi color mode          
          if ((counter & 1) == 0) gdata <<= 2
          val cbit = (gdata & 0x300) >> 8
          cbit match {
            case 0 => if (isInDisplayState) getPixel(backgroundColor(cbit), false) // background
            		  else getPixel(backgroundColor(0),false)
            case 1 => if (isInDisplayState) getPixel(backgroundColor(cbit), false) // background
            		  else getPixel(0,false)
            case 2 => if (isInDisplayState) getPixel(backgroundColor(cbit)) // foreground
            		  else getPixel(0,true)
            case 3 => if (isInDisplayState) getPixel(vml_c(vmli) & 7)  // foreground
            		  else getPixel(0,true)
          }
        }
      } else { // bitmap mode
        if (!mcm) { // standard mode
          gdata <<= 1
          val cbit = (gdata & 0x100) == 0x100
          val col0 = vml_p(vmli) & 0x0F
          val col1 = (vml_p(vmli) >> 4) & 0x0F
          if (cbit) {
            if (isInDisplayState) getPixel(col1) // foreground
            else getPixel(0,true) 
          }
          else {
            if (isInDisplayState) getPixel(col0, false) // background
            else getPixel(0,false)
          }
        } else { // multi color mode          
          if ((counter & 1) == 0) gdata <<= 2
          val cbit = gdata & 0x300
          cbit match {
            case 0x00 => if (isInDisplayState) getPixel(backgroundColor(0), false) // background
            			 else getPixel(backgroundColor(0),false)
            case 0x100 => if (isInDisplayState) getPixel((vml_p(vmli) >> 4) & 0x0F, false) // background
            			  else getPixel(0,false)
            case 0x200 => if (isInDisplayState) getPixel(vml_p(vmli) & 0x0F)// foreground
            			  else getPixel(0,true)
            case 0x300 => if (isInDisplayState) getPixel(vml_c(vmli))// foreground
            		 	  else getPixel(0,true)
          }
        }
      }
      counter += 1
      if (xscroll == 0) {
        if (traceRasterLineInfo && pixel != BLACK_PIXEL) pixel.source = if (bmm) 'X' else 'G'
        pixel
      } else {
        if (firstPixel) {
          firstPixel = false
          var i = 0
          // insert xscroll black pixels
          while (i < xscroll) {
            xscrollBuffer(i) = getPixel(backgroundColor(0), false) //BLACK_PIXEL
            i += 1
          }
        }
        xscrollBuffer(xscroll) = pixel
        // shift xscrollBuffer
        val headPixel = xscrollBuffer(0)
        var i = 0
        while (i < xscroll) {
          xscrollBuffer(i) = xscrollBuffer(i + 1)
          i += 1
        }
        if (traceRasterLineInfo && pixel != BLACK_PIXEL) headPixel.source = 'G'
        headPixel
      }
    }

    final def producePixels {
      isBlank = VIC.this.isBlank
      mcm = VIC.this.mcm
      ecm = VIC.this.ecm
      vml_p = VIC.this.vml_p
      vml_c = VIC.this.vml_c
      vmli = VIC.this.vmli

      // light pen checking
      var xcoord = 0
      var lpx = 0
      var baseX = 0
      val checkLP = lightPenEnabled && rasterLine == display.getLightPenY
      if (checkLP) {
        xcoord = xCoord
        lpx = display.getLightPenX
        baseX = rasterCycle << 3
      }
      var i = 0
      while (!isFinished) {
        pixels(i) = shift
        if (checkLP && baseX + i == lpx) triggerLightPen
        i += 1
      }
    }
  }

  def init {
    sprites foreach { add _ }
  }

  override def getProperties = {    
      properties.setProperty("Video matrix addess",Integer.toHexString(videoMatrixAddress))
      properties.setProperty("Character address",Integer.toHexString(characterAddress))
      properties.setProperty("Bitmap address",Integer.toHexString(bitmapAddress))
      properties.setProperty("X scroll",xscroll.toString)
      properties.setProperty("Y scroll",yscroll.toString)
      properties.setProperty("Bitmap mode",bmm.toString)
      properties.setProperty("Extended color mode",ecm.toString)
      properties.setProperty("Multicolor mode",mcm.toString)
      properties.setProperty("Raster line",Integer.toHexString(rasterLine))
      properties.setProperty("Sprite enable register",Integer.toHexString(spriteEnableRegister))
      properties.setProperty("Control register 1",Integer.toHexString(controlRegister1))
      properties.setProperty("Control register 2",Integer.toHexString(controlRegister2))
      properties.setProperty("VIC base address",Integer.toHexString(vicBaseAddress))
      properties.setProperty("IRQ control register",Integer.toHexString(interruptControlRegister))
      properties.setProperty("IRQ mask register",Integer.toHexString(interruptMaskRegister))
      properties.setProperty("Raster latch",Integer.toHexString(rasterLatch))
      super.getProperties
    }

  def reset {
    videoMatrixAddress = 0
    characterAddress = 0
    bitmapAddress = 0
    vcbase = 0
    vc = 0
    rc = 0
    vmli = 0
    isInDisplayState = false
    yscroll = 0
    xscroll = 0
    rsel = 0
    den = false
    denOn30 = false
    bmm = false
    ecm = false
    csel = 0
    mcm = false
    res = false
    mainBorderFF = false
    verticalBorderFF = false
    rasterCycle = 0
    spriteXCoord9thBit = 0
    controlRegister1 = 0x9B
    rasterLine = 0
    rasterLatch = 0
    spriteEnableRegister = 0
    controlRegister2 = 8
    spriteYExpansion = 0
    vicBaseAddress = 0x14
    interruptControlRegister = 0
    interruptMaskRegister = 0
    spriteCollisionPriority = 0
    spriteMulticolor = 0
    spriteXExpansion = 0
    spriteSpriteCollision = 0
    spriteBackgroundCollision = 0
    for (s <- 0 to 7) sprites(s).clear
    BorderShifter.clear
    GFXShifter.clear
  }

  def setDisplay(display: Display) = {
    this.display = display
    displayMem = display.displayMem
    if (clip)
      display.setClipArea((BLANK_LEFT_CYCLE + 1) * 8, BLANK_TOP_LINE + 1, BLANK_RIGHT_CYCLE * 8, BLANK_BOTTOM_LINE)
  }

  def enableLightPen(enabled: Boolean) = lightPenEnabled = enabled

  def triggerLightPen {
    if (canUpdateLightPenCoords) {
      canUpdateLightPenCoords = false
      lightPenXYCoord(0) = (xCoord >> 1) & 0xFF
      lightPenXYCoord(1) = rasterLine & 0xFF
      interruptControlRegister |= 8
      // check if we must set interrupt
      if ((interruptControlRegister & interruptMaskRegister & 0x0f) != 0) irqRequest
    }
  }

  @inline private def decodeAddress(address: Int) = (address - startAddress) % 64

  final def read(address: Int, chipID: ChipID.ID): Int = {
    val offset = decodeAddress(address)
    if (offset <= 0xF) spriteXYCoord(offset)
    else if (offset >= 0x2F && offset <= 0x3F) 0xFF
    else
      offset match {
        case 16 => spriteXCoord9thBit
        case 17 => controlRegister1
        case 18 => rasterLine & 0xFF
        case 19 => lightPenXYCoord(0)
        case 20 => lightPenXYCoord(1)
        case 21 => spriteEnableRegister
        case 22 => controlRegister2 | 0xC0 // bit 6 & 7 always 1
        case 23 => spriteYExpansion
        case 24 => vicBaseAddress | 1 // bit 0 always 1
        case 25 => interruptControlRegister | 0x70 //& 0x8F // bit 4,5,6 always 1
        case 26 => interruptMaskRegister | 0xF0 // bit 7,6,5,4 always 1
        case 27 => spriteCollisionPriority
        case 28 => spriteMulticolor
        case 29 => spriteXExpansion
        case 30 =>
          val tmp = spriteSpriteCollision; spriteSpriteCollision = 0; tmp // cleared after reading
        case 31 =>
          val tmp = spriteBackgroundCollision; spriteBackgroundCollision = 0; tmp // cleared after reading
        case 32 => borderColor | 0xF0 // bit 7,6,5,4 always 1
        case 33 => backgroundColor(0) | 0xF0 // bit 7,6,5,4 always 1
        case 34 => backgroundColor(1) | 0xF0 // bit 7,6,5,4 always 1
        case 35 => backgroundColor(2) | 0xF0 // bit 7,6,5,4 always 1
        case 36 => backgroundColor(3) | 0xF0 // bit 7,6,5,4 always 1
        case 37 => spriteMulticolor01(0) | 0xF0 // bit 7,6,5,4 always 1
        case 38 => spriteMulticolor01(1) | 0xF0 // bit 7,6,5,4 always 1
        case 39 => spriteColor(0) | 0xF0 // bit 7,6,5,4 always 1
        case 40 => spriteColor(1) | 0xF0 // bit 7,6,5,4 always 1
        case 41 => spriteColor(2) | 0xF0 // bit 7,6,5,4 always 1
        case 42 => spriteColor(3) | 0xF0 // bit 7,6,5,4 always 1
        case 43 => spriteColor(4) | 0xF0 // bit 7,6,5,4 always 1
        case 44 => spriteColor(5) | 0xF0 // bit 7,6,5,4 always 1
        case 45 => spriteColor(6) | 0xF0 // bit 7,6,5,4 always 1
        case 46 => spriteColor(7) | 0xF0 // bit 7,6,5,4 always 1
        case _ => 0xFF // $D02F-$D03F
      }
  }

  final def write(address: Int, value: Int, chipID: ChipID.ID) = {
    val offset = decodeAddress(address)
    if (offset <= 0xF) {
      spriteXYCoord(offset) = value
      val sindex = offset / 2
      val isX = (offset & 1) == 0
      if (isX) {
        val bit9 = sprites(sindex).x & 0x100
        sprites(sindex).x = value | bit9
      } else sprites(sindex).y = value
      //Log.fine(s"Sprite #${sindex} update coord ${if (isX) "x" else "y"} = ${value}")
    } else
      offset match {
        case 16 =>
          spriteXCoord9thBit = value
          var i = 0
          var tmp = spriteXCoord9thBit
          while (i < 8) {
            if ((tmp & 1) == 1) sprites(i).x |= 0x100 else sprites(i).x &= 0xFF
            tmp >>= 1
            i += 1
          }
        //Log.fine((for(i <- 0 to 7) yield "Sprite x updated to" + sprites(i).x) mkString("\n"))
        case 17 =>
          controlRegister1 = value
          if ((controlRegister1 & 128) == 128) rasterLatch |= 0x100 else rasterLatch &= 0xFF
          if (rasterLatch > RASTER_LINES) rasterLatch &= 0xFF
          //if (rasterLine == rasterLatch) rasterLineEqualsLatch
          yscroll = controlRegister1 & 7
          rsel = (controlRegister1 & 8) >> 3
          den = (controlRegister1 & 16) == 16
          bmm = (controlRegister1 & 32) == 32
          ecm = (controlRegister1 & 64) == 64
          if (debug) Log.info("VIC control register set to %s, yscroll=%d rsel=%d den=%b bmm=%b ecm=%b. Raster latch set to %4X".format(Integer.toBinaryString(controlRegister1), yscroll, rsel, den, bmm, ecm, rasterLatch))
        //Log.info("VIC control register set to %s, yscroll=%d rsel=%d den=%b bmm=%b ecm=%b. Raster latch set to %4X".format(Integer.toBinaryString(controlRegister1),yscroll,rsel,den,bmm,ecm,rasterLatch))
        case 18 =>
          //Log.debug("VIC previous value of raster latch is %4X".format(rasterLatch))
          val rst8 = rasterLatch & 0x100
          rasterLatch = value | rst8
          if (rasterLatch > RASTER_LINES) rasterLatch &= 0xFF
          //if (rasterLine == rasterLatch) rasterLineEqualsLatch
          if (debug) Log.info("VIC raster latch set to %4X value=%2X".format(rasterLatch, value))
        //else Log.debug("VIC raster latch set to %4X value=%2X".format(rasterLatch,value))
        case 19 | 20 => // light pen ignored
        case 21 =>
          spriteEnableRegister = value
          var i = 7
          while (i >= 0) {
            sprites(i).enabled = ((spriteEnableRegister >> i) & 1) == 1
            i -= 1
            //if (sprites(i).enabled) Log.debug(s" Sprite #${i} enabled. " + sprites(i))
          }
        //Log.fine("Sprite enable register se to " + Integer.toBinaryString(spriteEnableRegister))
        case 22 =>
          controlRegister2 = value
          xscroll = controlRegister2 & 7
          csel = (controlRegister2 & 8) >> 3
          mcm = (controlRegister2 & 16) == 16
          res = (controlRegister2 & 32) == 32
          if (debug) Log.info("VIC control register 2 set to %s, xscroll=%d csel=%d mcm=%b rasterLine=%d".format(Integer.toBinaryString(controlRegister2), xscroll, csel, mcm, rasterLine))
        //Log.info("VIC control register 2 set to %s, xscroll=%d csel=%d mcm=%b rasterLine=%d".format(Integer.toBinaryString(controlRegister2),xscroll,csel,mcm,rasterLine))
        case 23 =>
          spriteYExpansion = value
          var i = 7
          while (i >= 0) {
            sprites(i).yexp = ((spriteYExpansion >> i) & 1) == 1
            i -= 1
          }
        //Log.fine("Sprite Y expansion se to " + Integer.toBinaryString(spriteYExpansion))
        case 24 =>
          vicBaseAddress = value
          videoMatrixAddress = ((vicBaseAddress >> 4) & 0x0F) << 10 //* 1024
          characterAddress = ((vicBaseAddress >> 1) & 0x07) << 11 //* 2048
          bitmapAddress = ((vicBaseAddress >> 3) & 1) << 13 //* 8192
          if (debug) Log.info(s"VIC base pointer set to ${Integer.toBinaryString(vicBaseAddress)} video matrix=${videoMatrixAddress} char address=${characterAddress} bitmap address=${bitmapAddress}")
        //Log.info(s"VIC base pointer set to ${Integer.toBinaryString(vicBaseAddress)} video matrix=${videoMatrixAddress} char address=${characterAddress} bitmap address=${bitmapAddress} raster=${rasterLine}")
        case 25 =>
//          val tmp = if ((value & 0x80) != 0) 0xFF else value
//          interruptControlRegister &= 0xFF ^ tmp
          interruptControlRegister &= ~interruptControlRegister & 0x0F
          // check if we must clear interrupt
          if ((interruptControlRegister & interruptMaskRegister & 0x0f) == 0) {
            //Log.debug("VIC clearing interrupt...")
            interruptControlRegister &= 0x7F
            irqAction(false)
          }
        // light pen ignored
        //Log.debug("VIC interrupt control register set to " + Integer.toBinaryString(interruptControlRegister))
        case 26 =>
          interruptMaskRegister = value & 0x0F
          // check if we must set interrupt
          if ((interruptControlRegister & interruptMaskRegister & 0x0f) != 0) {
            interruptControlRegister |= 0x80
            //Log.debug("VIC#26 setting interrupt...")
            irqRequest
          } else {
            interruptControlRegister &= 0x7F
            irqAction(false)
          }

        //Log.debug("VIC interrupt mask register set to " + Integer.toBinaryString(interruptMaskRegister))
        case 27 =>
          spriteCollisionPriority = value
          var i = 7
          while (i >= 0) {
            sprites(i).dataPriority = ((spriteCollisionPriority >> i) & 1) == 1
            i -= 1
          }
        //Log.fine("Sprite collision priority set to " + Integer.toBinaryString(spriteCollisionPriority))
        case 28 =>
          spriteMulticolor = value
          var i = 7
          while (i >= 0) {
            sprites(i).isMulticolor = ((spriteMulticolor >> i) & 1) == 1
            i -= 1
          }
        //Log.fine("Sprite multicolor set to " + Integer.toBinaryString(spriteMulticolor))
        case 29 =>
          spriteXExpansion = value
          var i = 7
          while (i >= 0) {
            sprites(i).xexp = ((spriteXExpansion >> i) & 1) == 1
            i -= 1
          }
        //Log.fine("Sprite X expansion set to " + Integer.toBinaryString(spriteXExpansion))
        case 30 | 31 => // can't be written
        case 32 =>
          borderColor = value & 0x0F
        //Log.debug("VIC border color set to " + borderColor)
        case 33 | 34 | 35 | 36 =>
          backgroundColor(offset - 33) = value & 0x0F
        //Log.debug("VIC background color #%d set to %d".format(offset - 33,value))
        case 37 | 38 =>
          spriteMulticolor01(offset - 37) = value & 0x0F
        //Log.fine("Sprite multicolor #%d set to %d".format(offset - 37,value))
        case 39 | 40 | 41 | 42 | 43 | 44 | 45 | 46 =>
          val index = offset - 39
          spriteColor(index) = value & 0x0F
          sprites(index).color = value & 0x0F
        //Log.fine("Sprite #%d color set to %d".format(offset - 39,value))
        case _ => // $D02F-$D03F
      }
  }

  @inline private def spriteCheck {
    var c = 0
    while (c < 8) {
      sprites(c).checkForCycle(rasterCycle)
      c += 1
    }
  }

  final def clock(cycles: Long) {
    isBlank = rasterLine <= BLANK_TOP_LINE || rasterLine >= BLANK_BOTTOM_LINE || rasterCycle <= BLANK_LEFT_CYCLE || rasterCycle >= BLANK_RIGHT_CYCLE
    val badLine = isBadLine

    if (badLine) isInDisplayState = true

    if (rasterLine == 0) {
      vcbase = 0
      vc = 0
    }

    //Log.fine(s"VIC rasterLine=${rasterLine} vicCycle=${rasterCycle} vcbase=${vcbase} vc=${vc} vmli=${vmli} mainFF=${mainBorderFF} verticalFF=${verticalBorderFF} displayState=${isInDisplayState} Xcoord=${xCoord}")
    // check sprite cycle
    SPRITE_READ_CYCLE(rasterCycle) match {
      case -1 =>
        // check sprite ba
        SPRITE_BA_CYCLE(rasterCycle) match {
          case -1 =>
          case s if sprites(s).dma =>
            baLow(cycles + BA_CYCLES_PER_SPRITE)
          case _ =>
        }
      case s => 
        sprites(s).readMemoryData(cycles)
    }
    rasterCycle match {
      case 0 =>
        // check raster line with raster latch if irq enabled     
        if (rasterLine > 0 && rasterLine == rasterLatch) rasterLineEqualsLatch
        // check den on $30
        if (rasterLine == 0x30) denOn30 = den
        drawCycle(-1)
      // ---------------------------------------------------------------  
      case 1 =>
        // check raster line with raster latch if irq enabled    
        if (rasterLine == 0 && rasterLine == rasterLatch) rasterLineEqualsLatch
        drawCycle(-1)
      case 2 =>
        drawCycle(-1)
      case 3 =>
        drawCycle(-1)
      case 4 =>
        drawCycle(-1)
      case 5 =>
        drawCycle(-1)
      case 6 =>
        drawCycle(-1)
      case 7 =>
        drawCycle(-1)
      case 8 =>
        drawCycle(-1)
      case 9 =>
        drawCycle(-1)
      case 10 =>
        drawCycle(-1)
      case 11 =>
        drawCycle(-1)
      // ---------------------------------------------------------------
      case 55 =>
        drawCycle(-1)
      case 56 =>
        spriteCheck
        drawCycle(-1)
      case 57 =>
        spriteCheck
        if (rc == 7) {
          isInDisplayState = false
          vcbase = vc
          //Log.fine(s"VIC cycle 58 vcbase=${vcbase}")
        }
        if (badLine || isInDisplayState) rc = (rc + 1) & 7
        drawCycle(-1)	
      // ---------------------------------------------------------------
      case 58 =>     
        drawCycle(-1)
      // ---------------------------------------------------------------
      case 59 =>        
        drawCycle(-1)
      case 60 =>
        drawCycle(-1)
      case 61 =>
        drawCycle(-1)
      case 62 =>
        GFXShifter.reset
        drawCycle(-1)
      // ---------------------------------------------------------------
      case _ => // 12 - 54
        rasterCycle match {
          case 12 =>
            if (badLine) baLow(cycles + BA_CYCLES_FOR_CHARS)
          case 13 =>
            vc = vcbase
            vmli = 0
            if (badLine) rc = 0
          case 15 | 16 =>
            spriteCheck
          case 54 =>
            spriteCheck
          case _ =>
        }

        if (isInDisplayState && rasterCycle >= 15) {
          if (badLine) readAndStoreVideoMemory
          // g-access
          drawCycle(readCharFromMemory)
          if (isInDisplayState) {
            vc = (vc + 1) & 0x3FF //% 1024
            vmli = (vmli + 1) & 0x3F //% 64
          }
        } 
        else 
        if (!isInDisplayState && rasterCycle >= 15) {
          //vml_c(vmli) = 0
          drawCycle(mem.read(if (ecm) 0x39ff else 0x3fff))
          //drawCycle(0)
        } 
        else drawCycle(-1)
    }
    // at the end increase the cycle
    rasterCycle = (rasterCycle + 1) % RASTER_CYCLES
    if (rasterCycle == 0) {
      rasterLine = (rasterLine + 1) % RASTER_LINES
      // update the 8th bit of raster in control register 1
      if (rasterLine > 0xFF) controlRegister1 |= 0x80 else controlRegister1 &= 0x7F
      if (rasterLine == 0) {
        canUpdateLightPenCoords = true
        display.showFrame(firstModPixelX, firstModPixelY, lastModPixelX, lastModPixelY)
        firstModPixelX = -1
        lastModPixelX = -1
      }
    }
  }
  @inline private[this] def tracePixel(pixel: AbstractPixel, x: Int) {
    traceRasterLineBuffer(x) = "%c%2d%c%s%s".format(pixel.source, pixel.colorIndex, if (pixel.isForeground) 'F' else 'B', if (mcm) "M" else "", if (ecm) "E" else "")
  }
  @inline private[this] def drawPixel(x: Int, y: Int, pixel: AbstractPixel) = {
    val index = y * SCREEN_WIDTH + x
    if (displayMem(index) != pixel.color) {
      displayMem(index) = pixel.color
      if (firstModPixelX == -1) {
        firstModPixelX = x
        firstModPixelY = y
      }

      lastModPixelX = x
      lastModPixelY = y
    }
    if (traceRasterLineInfo && y == traceRasterLine) tracePixel(pixel, x)
  }

  private def drawCycle(gdata: Int) {
    if (isBlank) return

    val y = rasterLine
    val x = rasterCycle << 3
    var s, i = 0

    // --------------------- GFX -------------------------
    if (gdata >= 0) {
      GFXShifter.setData(gdata)
      GFXShifter.producePixels
    }
    // ------------------- Sprites -----------------------
    var almostOneSprite = false
    while (s < 8) {
      //if (sprites(s).enabled) sprites(s).producePixels
      if (sprites(s).displayable) {
        sprites(s).producePixels
        almostOneSprite = true
      }
      s += 1
    }
    // ------------------- Border ------------------------
    BorderShifter.setData(0)
    BorderShifter.producePixels
    // ************************** RENDERING ************************************

    val gfxPixels = GFXShifter.getPixels
    val borderPixels = BorderShifter.getPixels
    while (i < 8) { // scan each bit      
      s = 7
      var pixel: AbstractPixel = TransparentPixel
      if (almostOneSprite) while (s >= 0) { // scan each sprite
        val spritePixels = sprites(s).getPixels
        if (sprites(s).hasPixels && !spritePixels(i).isTransparent) {
          if (!pixel.isTransparent) spriteSpriteCollision(pixel.spriteIndex, spritePixels(i).spriteIndex) // sprite-sprite collision detected
          pixel = spritePixels(i)
        }
        s -= 1
      }
      //val gfxPixel = if (gdata < 0) BLACK_PIXEL else gfxPixels(i)
      val gfxPixel = if (gdata < 0) BLACK_PIXEL else gfxPixels(i)
      /*val gfxPixel = if (gdata < 0) {
        val p = getPixel(if (isInDisplayState) backgroundColor(0) else 0, false)
        if (traceRasterLineInfo) p.source = '-'
        p
      } else gfxPixels(i)*/
      if (gfxPixel.isForeground && !pixel.isTransparent) spriteDataCollision(pixel.spriteIndex) // sprite-data collision detected
      if (pixel.isTransparent || (gfxPixel.isForeground && pixel.behind)) pixel = gfxPixel
      if (!borderPixels(i).isTransparent) drawPixel(x + i, y, borderPixels(i))
      else {
        //println(s"Drawing sprite #${s} pixel at ${x + i},${y}: raster=${rasterLine} cycle=${rasterCycle} ${sprites(s).pixels(i)}")
        drawPixel(x + i, y, pixel)
      }
      i += 1
    }
    // ************************** RESET SPRITES ********************************
    s = 0
    if (almostOneSprite) while (s < 8) {
      if (sprites(s).hasPixels) sprites(s).reset
      s += 1
    }
    // *************************************************************************

    //if (debug) { display.showFrame }
  }

  @inline private def irqRequest {
    interruptControlRegister |= 128
    //Log.debug(s"Requesting IRQ raster=${rasterLine} interruptControlRegister=${Integer.toBinaryString(interruptControlRegister)}")
    //println(s"Requesting IRQ raster=${rasterLine} rasterLatch=${rasterLatch} interruptControlRegister=${Integer.toBinaryString(interruptControlRegister)}")
    irqAction(true)
  }

  @inline private def rasterLineEqualsLatch {
//    if ((interruptControlRegister & 1) == 0) {
      interruptControlRegister |= 1
      if ((interruptMaskRegister & 1) == 1) {
        //Log.debug(s"VIC - raster interrupt request raster=${rasterLine}")
        irqRequest
      }
//    }
  }

  @inline private def spriteSpriteCollision(i: Int, j: Int) {
    val mask = (1 << i) | (1 << j)
    if ((spriteSpriteCollision & mask) == 0) {
      spriteSpriteCollision |= mask
      interruptControlRegister |= 4
      if ((interruptMaskRegister & 4) == 4) {
        //Log.debug(s"Sprite-sprite collision detected between ${i} and ${j} ss=${Integer.toBinaryString(spriteSpriteCollision)} icr=${Integer.toBinaryString(interruptControlRegister)}")
        irqRequest
      }
    }
  }
  @inline private def spriteDataCollision(i: Int) {
    val mask = (1 << i)
    if ((spriteBackgroundCollision & mask) == 0) {
      spriteBackgroundCollision |= (1 << i)
      interruptControlRegister |= 2
      if ((interruptMaskRegister & 2) == 2) {
        //Log.debug(s"Sprite-data collision detected for ${i} sd=${Integer.toBinaryString(spriteBackgroundCollision)} icr=${Integer.toBinaryString(interruptControlRegister)}")
        irqRequest
      }
    }
  }

  /**
   * To be called on bad lines
   */
  @inline private def readAndStoreVideoMemory {
    val charCode = mem.read(videoMatrixAddress | vc)
    val color = mem.read(COLOR_ADDRESS | vc) & 0x0F
    vml_p(vmli) = charCode
    vml_c(vmli) = color
    //Log.fine(s"Reading video memory at ${videoMatrixAddress + offset}: charCode=${charCode} color=${color}")
  }
  
  @inline private def readCharFromMemory = {
    if (bmm) {
      val offset = bitmapAddress | ((vc & 0x3ff) << 3) | rc
      val bitmap = mem.read(offset)
      //Log.debug(s"Reading bitmap at ${offset} = ${bitmap}")
      bitmap
    } else {
      val charCode = if (ecm) vml_p(vmli) & 0x3F else vml_p(vmli)
      val char = mem.read(characterAddress | (charCode << 3) | rc)
      //Log.fine(s"Reading char at ${characterAddress} for char code ${charCode} with rc=${rc} pattern=${char}")
      char
    }
  }

  /*
   * A Bad Line Condition is given at any arbitrary clock cycle, if at the
 	negative edge of �0 at the beginning of the cycle RASTER >= $30 and RASTER
 	<= $f7 and the lower three bits of RASTER are equal to YSCROLL and if the
 	DEN bit was set during an arbitrary cycle of raster line $30.
   */
  @inline private def isBadLine = rasterLine >= 0x30 && rasterLine <= 0xF7 && ((rasterLine & 7) == yscroll) && denOn30

  /**
   * Get the x coordinate based on current raster cycle.
   */
  @inline private def xCoord = {
    var xcoord = (rasterCycle - CYCLE_FOR_FIRST_XCOORD) << 3
    if (xcoord < 0) xcoord += PIXELS_PER_LINE
    xcoord
  }

  @inline private def checkBorderFF(xcoord: Int) {
    // 1
    if (xcoord == LEFT_RIGHT_FF_COMP(csel)(1)) mainBorderFF = true
    // 2
    if (rasterCycle == RASTER_CYCLES && rasterLine == TOP_BOTTOM_FF_COMP(rsel)(1)) verticalBorderFF = true
    // 3
    if (rasterCycle == RASTER_CYCLES && rasterLine == TOP_BOTTOM_FF_COMP(rsel)(0) && den) verticalBorderFF = false
    // 4
    if (xcoord == LEFT_RIGHT_FF_COMP(csel)(0) && rasterLine == TOP_BOTTOM_FF_COMP(rsel)(1)) verticalBorderFF = true
    // 5
    if (xcoord == LEFT_RIGHT_FF_COMP(csel)(0) && rasterLine == TOP_BOTTOM_FF_COMP(rsel)(0) && den) verticalBorderFF = false
    // 6
    if (xcoord == LEFT_RIGHT_FF_COMP(csel)(0) && !verticalBorderFF) mainBorderFF = false
  }

  def enableTraceRasterLine(enabled: Boolean) = traceRasterLineInfo = enabled
  def setTraceRasterLineAt(traceRasterLine: Int) = this.traceRasterLine = traceRasterLine
  def getTraceRasterLineInfo = {
    val line = traceRasterLine + "=" + (traceRasterLineBuffer mkString "|")
    for (i <- 0 until traceRasterLineBuffer.length) traceRasterLineBuffer(i) = ""
    line
  }

  def getMemory = mem

  def dump = {
    val sb = new StringBuffer("VIC dump:\n")
    mem match {
      case bm: BankedMemory => sb.append(s"Video Bank:\t\t\t${bm.getBank} = ${Integer.toHexString(bm.getBankAddress)}\n")
      case _ =>
    }
    sb.append(if (bmm) "Bitmap mode\n" else "Text mode\n")
    sb.append(s"Extended color mode\t\t${ecm}\n")
    sb.append(s"Multi color mode\t\t${mcm}\n")
    sb.append(s"Raster line\t\t\t${rasterLine}\n")
    sb.append(s"Display enabled\t\t\t${den}\n")
    sb.append(s"Display size\t\t\t${if (csel == 1) "40" else "38"}x${if (rsel == 1) "25" else "24"}\n")
    sb.append(s"Raster IRQ on\t\t\t${rasterLatch}\n")
    sb.append(s"Video matrix address\t\t${Integer.toHexString(videoMatrixAddress)}\n")
    sb.append(s"Char address\t\t\t${Integer.toHexString(characterAddress)}\n")
    sb.append(s"Bitmap address\t\t\t${Integer.toHexString(bitmapAddress)}\n")
    sb.toString
  }
}