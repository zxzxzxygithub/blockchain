package main

import (
	"fmt"
	"crypto/sha256"

	"github.com/hyperledger/fabric/core/chaincode/shim"
	sc "github.com/hyperledger/fabric/protos/peer"
)

// Define the Smart Contract structure
type SmartContract struct {
}

func (s *SmartContract) Init(APIstub shim.ChaincodeStubInterface) sc.Response {
	fmt.Println("Initing chaincode")
	_, args := APIstub.GetFunctionAndParameters()

	if len(args) > 10  {
		return shim.Error("Incorrect number of arguments. Expecting 1")
	}

	return shim.Success(nil)
}

func (s *SmartContract) Invoke(APIstub shim.ChaincodeStubInterface) sc.Response {

	// Retrieve the requested Smart Contract function and arguments
	function, args := APIstub.GetFunctionAndParameters()

	// Route to the appropriate handler function to interact with the ledger appropriately
	if function == "creditAccountInfo" {
		return s.creditAccountInfo(APIstub, args)
	} else if  function == "authAccount" {
		return s.authAccount(APIstub, args)
	}

	return shim.Error("Invalid Smart Contract function name.")
}

func (s *SmartContract) creditAccountInfo(APIstub shim.ChaincodeStubInterface, args []string) sc.Response {

	/*
		Arg1: Bank Name
		Arg2: Username
		Arg3: User id card
		Arg4: User Account
		Arg5: Mobile Phone
	 */


	if len(args) != 5 {
		fmt.Println("Incorrect number of arguments. Expecting 5")
		return shim.Error("Incorrect number of arguments. Expecting 5")
	}

	accountKey := args[0] + `-` + args[2]
	accountValue := args[1] + args[2] + args[3] + args[4]

	fmt.Println(fmt.Sprintf("Account key %s", accountKey))
	fmt.Println(fmt.Sprintf("Account value %s", accountValue))

	//keyHashGenerator := sha256.New()
	//keyHashGenerator.Write([]byte(accountKey))
	//keyHash := fmt.Sprintf("%x", keyHashGenerator.Sum(nil))
	//fmt.Println(fmt.Sprintf("generated key hash %s", keyHash))

	valueHashGenerator := sha256.New()
	valueHashGenerator.Write([]byte(accountValue))
	valueHash := valueHashGenerator.Sum(nil)
	fmt.Println(fmt.Sprintf("generated vale hash %x", valueHash))

	err := APIstub.PutState(accountKey, valueHash)
	if err != nil {
		fmt.Println(fmt.Sprintf("error in commit account info %s", err.Error()))
		shim.Error(fmt.Sprintf("error in commit account info %s", err.Error()))
	}

	return shim.Success(nil)
}

func (s *SmartContract) authAccount(APIstub shim.ChaincodeStubInterface, args []string) sc.Response {

	/*
		Arg1: Bank Name
		Arg2: User id card
	 */
	if len(args) != 2 {
		fmt.Println("Incorrect number of arguments. Expecting 5")
		return shim.Error("Incorrect number of arguments. Expecting 5")
	}

	accountKey := args[0] + `-` + args[1]

	fmt.Println(fmt.Sprintf("Account key %s", accountKey))

	//keyHashGenerator := sha256.New()
	//keyHashGenerator.Write([]byte(accountKey))
	//keyHash := fmt.Sprintf("%x", keyHashGenerator.Sum(nil))
	//
	//fmt.Println(fmt.Sprintf("Generated Hash %s", keyHash))

	data, err := APIstub.GetState(accountKey)
	if err != nil {
		fmt.Println(fmt.Sprintf("Error getting data %s", err.Error()))
		return shim.Error(fmt.Sprintf("Error getting data %s", err.Error()))
	}

	if len(data) == 0 {
		fmt.Println("DataNotAvailable")
		return shim.Error("DataNotAvailable")
	}

	fmt.Println(fmt.Sprintf("received data %s", fmt.Sprintf("%x", data)))

	return shim.Success(data)
}

// The main function is only relevant in unit test mode. Only included here for completeness.
func main() {

	// Create a new Smart Contract
	err := shim.Start(new(SmartContract))
	if err != nil {
		fmt.Printf("Error creating new Smart Contract: %s", err)
	}
}
