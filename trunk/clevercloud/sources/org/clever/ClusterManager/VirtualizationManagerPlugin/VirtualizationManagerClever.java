/*
 * The MIT License
 *
 * Copyright 2011 giovalenti.
 * Copyright 2012 giancarloalteri
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */     
package org.clever.ClusterManager.VirtualizationManagerPlugin;

import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import org.apache.log4j.Logger;
import org.clever.ClusterManager.VirtualizationManager.VirtualizationManagerPlugin;
import org.clever.Common.Communicator.Agent;
import org.clever.Common.Communicator.CmAgent;
import org.clever.Common.Communicator.MethodInvoker;
import org.clever.Common.Communicator.ModuleCommunicator;
import org.clever.Common.Exceptions.CleverException;
import org.clever.Common.Exceptions.LogicalCatalogException;
import org.clever.Common.VEInfo.DesktopVirtualization;
import org.clever.Common.VEInfo.StorageSettings;
import org.clever.Common.VEInfo.VEDescription;
import org.clever.Common.XMLTools.MessageFormatter;
import org.clever.Common.XMLTools.ParserXML;
import org.clever.HostManager.ServiceManager.ServiceObject;
import org.jdom.Element;


public class VirtualizationManagerClever implements VirtualizationManagerPlugin {
    private ModuleCommunicator mc;
    private Agent owner;
    private String version = "0.0.1";
    private String description = "Plugin per HTML5 remote desktop";
    private String name = "Virtualization Desktop Plugin";
    private Logger logger = null;
    private String HostManagerServiceGuacaTarget;
    private String agent;
    private String OS_service;

    private String RegisterVirtualDeskHTML5 = "Virtualization/RegisterVirtualDesktopHTML5";
    private String UnRegisterVirtualDeskHTML5 = "Virtualization/UnRegisterVirtualDesktopHTML5";
    private ParserXML pXML;
    
    // variabile d'appoggio per lo startup di un VM scritta dallo startvm e letta dal RegisterVirtualizationDesktopHTML5
    private String vm_tmp="";


    private String nodoMatchingVmHM="Matching_VM_HM";
    private String nodoVmRunning="VMs_Running";


    public VirtualizationManagerClever() throws Exception{
        this.logger = Logger.getLogger( "VirtualizationManager plugin" );
        this.logger.info("VirtualizationManager plugin created: ");
    }


    @Override
    public void init(Element params, Agent owner) throws CleverException {
        if(params!=null){
            //Read param from configuration_networkManager.xml
            this.HostManagerServiceGuacaTarget = params.getChildText("HostManagerServiceGuacaTarget");
        }
        //this.owner = owner;
        //If the data struct, for matching between VM and HM, isen't into DB then init it.
       if(!this.checkMatchingVmHMNode())
            this.initVmHMNodeDB();
         if(!this.checkVmRunningNode())
            this.initVmRunningDB(); 

    }
    private boolean checkVmRunningNode() throws CleverException{
       List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/"+this.nodoVmRunning); //XPath location with eventual predicate
        return (Boolean)this.owner.invoke("DatabaseManagerAgent", "checkAgentNode", true, params); 
    }

    private boolean checkMatchingVmHMNode() throws CleverException{
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/"+this.nodoMatchingVmHM); //XPath location with eventual predicate
        return (Boolean)this.owner.invoke("DatabaseManagerAgent", "checkAgentNode", true, params);
    }
    
     private void initVmRunningDB() throws CleverException{
         String node="<"+this.nodoVmRunning+"/>";
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add(""); //XPath location with eventual predicate
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
         
     }
    private void initVmHMNodeDB() throws CleverException{
        String node="<"+this.nodoMatchingVmHM+"/>";
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add(""); //XPath location with eventual predicate
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
    }

    private void dispatchToExtern(String host_manager, String agent, String OS_service, ServiceObject serviceobject) throws Exception{
          List params = new ArrayList();
          params.add(OS_service);
          params.add(serviceobject);

          if(!(Boolean)((CmAgent)this.owner).remoteInvocation(host_manager, agent, "ServiceUpdate", true, params))
              throw new CleverException("Host Manager sul proxy HTML5/VNC non raggiungibile");
    }

    @Override
    public void RegisterVirtualizationDesktopHTML5(DesktopVirtualization desktop) throws Exception {
        this.agent="ServiceManagerAgent";
        this.OS_service="guacamole";
        if(desktop.getUsername().isEmpty()){      // se non definito imposto un username=VM
            desktop.setUsername(this.vm_tmp);
        }
        desktop.setUserPassword(MD5.getMD5("vmware"));//la pass dovrebbe essere settata mediante qualche politica (DA VEDERE)
        ServiceObject serviceobject = new ServiceObject(desktop,this.RegisterVirtualDeskHTML5);
        this.dispatchToExtern(this.HostManagerServiceGuacaTarget, this.agent, this.OS_service, serviceobject); //send object to OS_service Manager of Tiny Clever
        this.updateDesktop(desktop,this.vm_tmp);
    }

    @Override
    /**
     * @param id virtual machine name identification: vmname|host
     */
    public void UnRegisterVirtualizationDesktopHTML5(String id) throws Exception {
        String vmname = id;
        this.agent="ServiceManagerAgent";
        this.OS_service="guacamole";
        ServiceObject serviceobject = new ServiceObject(vmname,this.UnRegisterVirtualDeskHTML5);
        this.dispatchToExtern(this.HostManagerServiceGuacaTarget, this.agent, this.OS_service, serviceobject); //send object to OS_service Manager of Tiny Clever
        this.updateDesktop(vmname);
    }

    
    /**
     * Update Sedna DB for insert new item
     * @param desktop Contains all informations for a new desktop virtualized. name VM, host, port, vnc password and virtualization password
     * @return
     * @throws CleverException
     * @throws Exception 
     */
    private void updateDesktop(DesktopVirtualization desktop,String id) throws CleverException{  
        
        
           List params = new ArrayList();
        
        
           params.add("VirtualizationManagerAgent");
           params.add("<desktop>"
                             + "<username>"+desktop.getUsername()+"</username>"
                             + "<password_user>"+desktop.getUserPassword()+"</password_user>"
                             + "<password_vnc_vm>"+desktop.getVmVNCPassword()+"</password_vnc_vm>"
                             + "<ip_vnc>"+desktop.getIpVNC()+"</ip_vnc>"
                             + "<port>"+desktop.getPort()+"</port>"
                    + "</desktop>");
           params.add("with");
           params.add("/org.clever.Common.VEInfo.VEDescription[./name/text()=\""+id+"\"]/desktop");
       
           this.owner.invoke("DatabaseManagerAgent", "updateNode", true, params);
        
        
        
        /* 
        String node="<VMHTML5 name=\""+desktop.getUsername() +"\" host=\""+desktop.getIpVNC()+"\"><port>"+desktop.getPort()+"</port><pass_vnc>"+desktop.getVmVNCPassword()+"</pass_vnc><pass>"+desktop.getUserPassword()+"</pass></VMHTML5>";
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add("/org.clever.Common.VEDescription[./name/text()='"+desktop.getUsername()+"']"); //XPath location with eventual predicate. Username coincide con il nome della vm
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
         
         */
    }
    
    /**
     * Update Sedna DB for delete an item
     * @param name name of virtual machine
     * @param host name or ip host
     * @return
     * @throws CleverException
     * @throws Exception 
     */
    private void updateDesktop(String name) throws CleverException{ 
        
        List params = new ArrayList();
        
        
           params.add("VirtualizationManagerAgent");
           params.add("<port>-1</port>");
           params.add("with");
           params.add("/org.clever.Common.VEInfo.VEDescription[./name/text()=\""+name+"\"]/desktop/port");
        
           this.owner.invoke("DatabaseManagerAgent", "updateNode", true, params);
        
        
        /*
        String location="/org.clever.Common.VEDescription[./name/text()=\""+name+"\"]/VMHTML5[(@name=\""+name+"\" and @host=\""+host+"\")]";
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(location); //XPath location with eventual predicate     
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
         */
    }


    /**
     * Insert new item for matching between Virtual Machine and Host Manager
     * @param id
     * @throws CleverException
     */
    public void InsertItemIntoMatchingVmHM(String id, String HostManagerTarget) throws CleverException {
         String node="<VM name=\""+id+"\" request=\""+new Date().toString()+"\""+">"
                       +"<host>"+HostManagerTarget+"</host>"
                    +"</VM>";
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add("/"+this.nodoMatchingVmHM);
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
    }
    public void InsertItemIntoMatchingSnHM(String id,String nameS,String HostManagerTarget) throws CleverException{
   String node="<VM name=\""+nameS+"\" request=\""+new Date().toString()+"\" snapshot=\""+true+"\" parent=\""+id+"\">"
                     +"<host>"+HostManagerTarget+"</host>"
              +"</VM>";
    List params = new ArrayList();
    params.add("VirtualizationManagerAgent");
    params.add(node);
    params.add("into");
    params.add("/"+this.nodoMatchingVmHM);
    this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
    }
    
 /**
  * This method registers a virtual environment and write everything into database Sedna
  * @param info
  * @param veD
  * @throws CleverException
  */
 @Override
 public void register(String info, VEDescription veD) throws CleverException{
         
        List params = new ArrayList();
        params.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
        this.owner.invoke("StorageManagerAgent", "registerVeNew", true, params);   
        params.clear(); 
        
        // the name of the virtual machine is required
        if(veD.getName().isEmpty()){
                            throw new LogicalCatalogException("VM name is required");
        }
        // check if there is a virtual machine with the same name
        params.add("VirtualizationManagerAgent");
        params.add(("/org.clever.Common.VEInfo.VEDescription[./name/text()='"+veD.getName()+"']/name/text()"));
        boolean r = (Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);     
        if(r==true){  
                throw new LogicalCatalogException("VM name already exist");
                }
        params.clear();

        String prova=MessageFormatter.messageFromObject(veD);
        params.add("VirtualizationManagerAgent");
        params.add(prova);
        params.add("into");
        params.add("");           
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
    }

 /**
  * Creation and/or registration of a virtual machine
  * @param id
  * @param targetHM
  * @param lock
  * @return
  * @throws CleverException
  */
    @Override
    public String createVM(String id,String targetHM,Integer lock) throws CleverException{
         boolean result=false;
         MethodInvoker mi=null;
         // check if into db Sedna exist name of the VM
         List params = new ArrayList();
         params.add("VirtualizationManagerAgent");
         params.add(("/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']/name/text()"));
         boolean r = (Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);     
            if(r==false){  
                throw new LogicalCatalogException("VM name not valid");
                }
         params.clear();
         params.add("VirtualizationManagerAgent");
         String location="/Matching_VM_HM/VM[@name='"+id+"']";
         params.add(location);
         r = (Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);
         //Insert into DB: mappigetAttributeNodeng VM - HM
         if(r==true){  
                throw new LogicalCatalogException("VM already exist");
                }
         params.clear();
         //Insert into DB: mapping VM - HM
         this.InsertItemIntoMatchingVmHM(id, targetHM);

         List params1 = new ArrayList();
         params1.add("VirtualizationManagerAgent");
         location="/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']";
         params1.add(location);
         String pathxml=(String) this.owner.invoke("DatabaseManagerAgent", "query", true, params1); 
         VEDescription veD =(VEDescription) MessageFormatter.objectFromMessage(pathxml);

         params.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
         params.add(targetHM);
         params.add(lock);
         // physical path
          String res=(String) this.owner.invoke("StorageManagerAgent", "lockManager", true, params); 
         
         List params2 = new ArrayList();
         params2.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
         Boolean check=(Boolean) this.owner.invoke("StorageManagerAgent", "check", true, params2);
        
         
         ((StorageSettings)veD.getStorage().get(0)).setDiskPath(res);
         
         params2 = new ArrayList();
         params2.add(id);
         params2.add( veD );
         //params2.add(res);
         
         if(!check){
             result=(Boolean)((CmAgent)this.owner).remoteInvocation(targetHM,"HyperVisorAgent", "registerVm", true, params2);
         }
         else{
              result=(Boolean)((CmAgent)this.owner).remoteInvocation(targetHM,"HyperVisorAgent", "createVm", true, params2);
   
         }
         /*
         String a="";
           a = (String) ((CmAgent) this.owner).remoteInvocation(targetHM,"TestAgentHm","fittizioHM", true, params2);
          */
         if(!result){
             return "creating VM "+id+" failed!";
         }
         return "VM " +id+ " created!";
     }

    /**
     * Starting VM
     * @param id
     * @return
     * @throws CleverException
     */
    @Override
    public boolean startVm(String id) throws CleverException{


        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);
       
        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        
        // insert intoDB
        String node="<VM name=\""+id+"\" request=\""+new Date().toString()+"\" started=\"\">"+HMTarget+"</VM>";
        params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add("/"+this.nodoVmRunning);
       
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
        
        params = new ArrayList();
        params.add(id);
        this.vm_tmp=id;
        boolean result = (Boolean) ((CmAgent) this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","startVm", true, params);
        return result;
    }

    /**
     * Stopping VM
     * @param id
     * @return
     * @throws CleverException
     */
    @Override
    public boolean stopVm(String id) throws CleverException{
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);

        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);

        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        params = new ArrayList();
        params.add(id);
        boolean result = (Boolean) ((CmAgent) this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","shutDownVm", true, params);
        
        if(result){
            params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/VMs_Running/VM[@name='"+id+"']");
  
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
        }

        return result;
    }  


    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getVersion() {
        return this.version;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    @Override
    public void setModuleCommunicator(ModuleCommunicator mc) {
        this.mc = mc;
    }

    @Override
    public ModuleCommunicator getModuleCommunicator() {
        return this.mc;
    }

    @Override
    public void setOwner(Agent owner) {
        this.owner=owner;
    }
    
 

 /**   salvo
     * Stopping VM
     * @param id
     * @return
     * @throws CleverException
     */
    @Override
    public boolean unregisterVm(String id) throws CleverException{
        String response=null;
        
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(("/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']"));
        Boolean x=(Boolean)this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);
        if(!x){
            response="VM is not registered";
            throw new CleverException(response);
            
        }
      
        params.clear();
        params.add("VirtualizationManagerAgent");
        params.add("/Matching_VM_HM/VM[@parent='"+id+"']/@name");
        String r=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        if(r.isEmpty()==false){
            response="Have to delete first these snapshot:"+r;
            throw new CleverException(response);
        }
        params.clear();
        params.add("VirtualizationManagerAgent");
        params.add("/Matching_VM_HM/VM[@name='"+id+"']");
        r=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        if(r.isEmpty()==false){
            response="Have to do first deletevm -n "+id;
            throw new CleverException(response);
        }
      
        params.clear();
        params.add("VirtualizationManagerAgent");
        params.add(("/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']"));
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
        params.clear();
     
        return true;
        
        
        
        
        
    }

    @Override
    public boolean resumeVm(String id) throws CleverException {
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);
        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        params.clear();
        params.add("VirtualizationManagerAgent");
        params.add("/VMs_Running/VM[@name='"+id+"']");
        Boolean r=(Boolean)this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);
        if(r){
            throw new CleverException("Vm is already running");
        }
        
        String node="<VM name=\""+id+"\" request=\""+new Date().toString()+"\" started=\"\">"+HMTarget+"</VM>";
        params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add(node);
        params.add("into");
        params.add("/"+this.nodoVmRunning);
 
        this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
        
        params = new ArrayList();
        params.add(id);
        this.vm_tmp=id;
        boolean result=(Boolean)((CmAgent) this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","resume", true, params);
      
        return result;
    }

    @Override
    public boolean stopVm(String id, Boolean poweroff) throws CleverException {
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);

        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);

        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        params = new ArrayList();
        params.add(id);
        params.add(true);
        boolean result = (Boolean) ((CmAgent) this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","shutDownVm", true, params);
        if(result){
            params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/VMs_Running/VM[@name='"+id+"']");
  
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
        }

        return result;
    }

    @Override
    public boolean suspendVm(String id) throws CleverException {
          List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);

        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);

        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        params = new ArrayList();
        params.add(id);
        boolean result = (Boolean) ((CmAgent) this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","suspend", true, params);
        
        if(result){
            params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/VMs_Running/VM[@name='"+id+"']");
  
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
        }

        return result;
    }
    @Override
public boolean TakeEasySnapshot(String id,String nameS,String description,String targetHM) throws CleverException{
         boolean result=false;
         MethodInvoker mi=null;
         // check if into db Sedna exist name of the VM
         List params = new ArrayList();
         params.add("VirtualizationManagerAgent");
         params.add(("/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']/name/text()"));
         boolean r = (Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);     
            if(r==false){  
                throw new LogicalCatalogException("VM name not valid");
                }
         params.clear();
         params.add("VirtualizationManagerAgent");
         String location="/Matching_VM_HM/VM[@name='"+nameS+"']";
         params.add(location);
         r = (Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);
         //Insert into DB: mappigetAttributeNodeng VM - HM
         if(r==true){  
                throw new LogicalCatalogException("SN already exist");
                }
         this.InsertItemIntoMatchingSnHM(id,nameS,targetHM);
         int lock=1;
         List params1 = new ArrayList();
         params1.add("VirtualizationManagerAgent");
         location="/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']";
         params1.add(location);
         String pathxml=(String) this.owner.invoke("DatabaseManagerAgent", "query", true, params1); 
         VEDescription veD =(VEDescription) MessageFormatter.objectFromMessage(pathxml);
         params.clear();
         params.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
         params.add(targetHM);
         params.add(lock);
         // physical path
         String localpath=(String)this.owner.invoke("StorageManagerAgent","lockManager", true, params);
         params.clear();
         params.add(localpath);
         params.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
         params.add(targetHM);
         params.add(lock);
         String snapshotLocalPath=(String)this.owner.invoke("StorageManagerAgent","SnapshotImageCreate", true, params);
   
        
        
         
         ((StorageSettings)veD.getStorage().get(0)).setDiskPath(snapshotLocalPath);
         veD.setName(nameS);
         params.clear();
         params.add(nameS);
         params.add( veD );
         
        result=(Boolean)((CmAgent)this.owner).remoteInvocation(targetHM,"HyperVisorAgent", "createVm", true, params);
            
       
         if(!result){
             throw new CleverException("creating SN "+nameS+" failed!");
         }
         return true;
     }
    @Override
 public boolean deleteVm(String id) throws CleverException{
        List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        params.add("/Matching_VM_HM/VM[@name='"+id+"']/host/text()");
        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        
        params.clear(); 
        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
         params.clear();
         params.add("VirtualizationManagerAgent");
         params.add("/Matching_VM_HM/VM[@name='"+id+"'][@snapshot='"+true+"']");
         Boolean r=(Boolean) this.owner.invoke("DatabaseManagerAgent", "existNode", true, params);
         params.clear();
        
         if(r){
            params.add("VirtualizationManagerAgent");
            params.add("/Matching_VM_HM/VM[@name='"+id+"']");
            params.add("parent");
            String parent=(String)this.owner.invoke("DatabaseManagerAgent", "getAttributeNode", true, params);
            
            params.clear();
           
            params.add("VirtualizationManagerAgent");
            String location="/org.clever.Common.VEInfo.VEDescription[./name/text()='"+parent+"']";
            params.add(location);
         }
       
         else{
                params.add("VirtualizationManagerAgent");
                String location="/org.clever.Common.VEInfo.VEDescription[./name/text()='"+id+"']";
                params.add(location);
         }
         
         
         String pathxml=(String) this.owner.invoke("DatabaseManagerAgent", "query", true, params); 
         VEDescription veD =(VEDescription) MessageFormatter.objectFromMessage(pathxml);
         params.clear();
        params.add("VirtualizationManagerAgent");
        params.add(("/Matching_VM_HM/VM[@name='"+id+"']"));
        this.owner.invoke("DatabaseManagerAgent", "deleteNode", true, params);
         params.clear();
         params.add(((StorageSettings)veD.getStorage().get(0)).getDiskPath());
         params.add(id);
         params.add(HMTarget);
         this.owner.invoke("StorageManagerAgent","deleteFile", true, params);
         params.clear();
         params.add(id);
         ((CmAgent)this.owner).remoteInvocation(HMTarget,"HyperVisorAgent", "unregisterVm", true, params);
         
        return true;
        
        
 }
public boolean attackInterface(String id,String inf,String mac,String type) throws CleverException{
  List params = new ArrayList();
        params.add("VirtualizationManagerAgent");
        String location="/Matching_VM_HM/VM[@name='"+id+"']/host/text()";
        params.add(location);
        String HMTarget=(String)this.owner.invoke("DatabaseManagerAgent", "query", true, params);
        if(HMTarget.isEmpty())
            throw new LogicalCatalogException("VM name not exist");
        params.clear();
        params.add(id);
        params.add(inf);
        params.add(mac);
        params.add(type);
        ((CmAgent)this.owner).remoteInvocation(HMTarget,"HyperVisorAgent","attackInterface", true, params); 
        boolean result=insertNetInterfaceIntoDb(id,inf,mac,type);
        return result;
 }
      
 
 public boolean insertNetInterfaceIntoDb(String id,String inf,String mac,String type) throws CleverException{
           String iface="<interface>"
                             + "<name>"+inf+"</name>"
                             + "<type>"+type+"</type>"
                             + "<mac_address>"+mac+"</mac_address>"
                       + "</interface>";
           List params=new ArrayList();
           params.add("VirtualizationManagerAgent");
           params.add(iface);
           params.add("into");
           params.add("/Matching_VM_HM/VM[@name='"+id+"']");
           this.owner.invoke("DatabaseManagerAgent", "insertNode", true, params);
        return  true;
           
       }
public String listMac_address(String id) throws CleverException{
        List params=new ArrayList();
        
        params.add("VirtualizationManagerAgent");
        params.add("/Matching_VM_HM/VM[@name='"+id+"']/interface/mac_address/text()");
        String result=(String)this.owner.invoke("DatabaseManagerAgent", "querytab", true, params);
        return result;
        
    } 


}

 
  

