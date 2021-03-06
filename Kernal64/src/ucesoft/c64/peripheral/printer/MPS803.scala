package ucesoft.c64.peripheral.printer

import ucesoft.c64.peripheral.bus.IECBusDevice
import ucesoft.c64.peripheral.bus.IECBus
import ucesoft.c64.cpu.Memory
import ucesoft.c64.util.CBMCanvas
import ucesoft.c64.C64Component
import ucesoft.c64.C64ComponentType
import scala.collection.mutable.ListBuffer
import java.io.ObjectOutputStream
import java.io.ObjectInputStream
import javax.swing.JFrame

class MPS803(bus:IECBus,driver:PrinterDriver,device:Int = 4) extends IECBusDevice(bus,device) with C64Component {
  val componentID = "Commodore MPS803"
  val componentType = C64ComponentType.PRINTER 
  
  val busid = "MPS803"
    
  private[this] var active = false
  
  // register itself to bus
  bus.registerListener(this)
  
  def setActive(active:Boolean) = this.active = active
  protected def isDeviceReady = active
  protected def loadData(fileName:String) = None
  
  def init {}
  
  def reset {}
  
  override def open_channel {
    channel match {
      case 7 => driver.print(17) 
      case _ => driver.print(145)
    }
  }
  
  override protected def byteJustRead(byte:Int,isLast:Boolean) {
    //println("MPS803: byte read " + Integer.toHexString(byte) + " " + byte.toChar + " last=" + isLast) 
    channels(channel).clear
    driver.print(byte)
  }
  // state
  protected def saveState(out:ObjectOutputStream) {
    out.writeBoolean(active)
  }
  protected def loadState(in:ObjectInputStream) {
    active = in.readBoolean
  }
  protected def allowsStateRestoring(parent:JFrame) : Boolean = true
}